package io.github.statusring.myron;

import android.content.*;
import android.net.*;
import android.net.wifi.*;
import android.os.*;
import android.provider.Settings;
import android.telephony.*;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.*;

/** Lives only inside SystemUI. It never changes radio, network, or SIM settings. */
public final class SignalStore {
    public final SignalModel model=new SignalModel();
    public boolean ready=false,wifiKnown=false;
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private SubscriptionManager subscriptions;
    private TelephonyManager telephony;
    private WifiManager wifi;
    private ConnectivityManager connectivity;
    private final List<Registration> registrations=new ArrayList<>();
    private String lastSignature="";
    private int dataSubscription=-1;
    private boolean started;
    private int generation;

    public SignalStore(Context c,Runnable listener){context=c.getApplicationContext();changed=listener;}
    private void update(){changed.run();}
    public void start(){
        if(started)return;started=true;
        try {
            subscriptions=context.getSystemService(SubscriptionManager.class);
            telephony=context.getSystemService(TelephonyManager.class);
            wifi=context.getSystemService(WifiManager.class);
            connectivity=context.getSystemService(ConnectivityManager.class);
            IntentFilter filter=new IntentFilter();
            filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            filter.addAction(Intent.ACTION_BATTERY_CHANGED);
            filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
            filter.addAction("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
            Intent sticky=context.registerReceiver(new BroadcastReceiver(){
                @Override public void onReceive(Context c,Intent i){
                    if(Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())){
                        int scale=i.getIntExtra(BatteryManager.EXTRA_SCALE,100);
                        int level=i.getIntExtra(BatteryManager.EXTRA_LEVEL,-1);
                        if(level>=0&&scale>0)model.battery=Config.clamp(Math.round(level*100f/scale),0,100);
                        int status=i.getIntExtra(BatteryManager.EXTRA_STATUS,0);
                        model.charging=status==BatteryManager.BATTERY_STATUS_CHARGING;
                    }
                    refreshAirplane();refreshWifi();refreshDataSlot();update();
                }
            },filter,Context.RECEIVER_EXPORTED);
            subscriptions.addOnSubscriptionsChangedListener(context.getMainExecutor(),new SubscriptionManager.OnSubscriptionsChangedListener(){
                @Override public void onSubscriptionsChanged(){rebuildSubscriptions();}
            });
            connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),new ConnectivityManager.NetworkCallback(){
                @Override public void onAvailable(Network n){main.post(()->{refreshWifi();update();});}
                @Override public void onLost(Network n){main.post(()->{refreshWifi();update();});}
                @Override public void onCapabilitiesChanged(Network n,NetworkCapabilities cap){
                    // A queued capabilities callback can outlive its network. Read the
                    // current state instead of reapplying that callback's stale WifiInfo.
                    main.post(()->{refreshWifi();update();});
                }
            },main);
            refreshAirplane();refreshWifi();rebuildSubscriptions();
        }catch(Throwable t){ready=false;Log.e("StatusRing","Cannot initialize real signal state; stock icons retained",t);}
    }
    private void refreshAirplane(){
        model.airplane=Settings.Global.getInt(context.getContentResolver(),Settings.Global.AIRPLANE_MODE_ON,0)!=0;
    }
    private void refreshDataSlot(){
        dataSubscription=SubscriptionManager.getDefaultDataSubscriptionId();model.dataSlot=-1;
        for(Registration r:registrations)if(r.id==dataSubscription)model.dataSlot=r.slot;
    }
    private void refreshWifi(){
        // Clear disconnected state first: a failed query must never retain a stale Wi-Fi icon.
        model.wifi=false;model.wifiLevel=0;wifiKnown=false;
        try{
            if(wifi==null)return;
            if(!wifi.isWifiEnabled()){wifiKnown=true;return;}
            for(Network n:connectivity.getAllNetworks()){
                NetworkCapabilities caps=connectivity.getNetworkCapabilities(n);
                if(caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)){
                    model.wifi=true;
                    TransportInfo transport=caps.getTransportInfo();
                    WifiInfo info=transport instanceof WifiInfo?(WifiInfo)transport:wifi.getConnectionInfo();
                    if(info!=null&&info.getRssi()>-127)model.wifiLevel=WifiManager.calculateSignalLevel(info.getRssi(),5);
                    break;
                }
            }
            wifiKnown=true;
        }catch(Throwable t){Log.w("StatusRing","Wi-Fi snapshot unavailable");}
    }
    private void rebuildSubscriptions(){
        try{
            List<SubscriptionInfo> all=subscriptions.getActiveSubscriptionInfoList();
            if(all==null)all=Collections.emptyList();
            List<SubscriptionInfo> physical=new ArrayList<>();
            for(SubscriptionInfo info:all)if(info.getSimSlotIndex()>=0&&info.getSimSlotIndex()<2)physical.add(info);
            physical.sort(Comparator.comparingInt(SubscriptionInfo::getSimSlotIndex));
            StringBuilder signature=new StringBuilder();
            for(SubscriptionInfo info:physical)signature.append(info.getSimSlotIndex()).append(':').append(info.getSubscriptionId()).append(';');
            if(ready&&signature.toString().equals(lastSignature)){refreshDataSlot();update();return;}
            generation++;
            for(Registration r:registrations)try{r.manager.unregisterTelephonyCallback(r.callback);}catch(Throwable ignored){}
            registrations.clear();Arrays.fill(model.levels,-2);Arrays.fill(model.types,"");
            int successful=0;
            for(SubscriptionInfo info:physical){
                int slot=info.getSimSlotIndex();model.levels[slot]=-1;
                TelephonyManager perSim=telephony.createForSubscriptionId(info.getSubscriptionId());
                CellCallback cb=new CellCallback(slot,perSim);
                try{
                    perSim.registerTelephonyCallback(context.getMainExecutor(),cb);
                    registrations.add(new Registration(info.getSubscriptionId(),slot,perSim,cb));successful++;
                    cb.sample();
                }catch(Throwable t){Log.e("StatusRing","Cannot register physical SIM "+(slot+1),t);}
            }
            ready=physical.isEmpty()||successful==physical.size();lastSignature=signature.toString();refreshDataSlot();update();
            Log.i("StatusRing","Real signal subscriptions ready="+ready+", slots="+physical.size());
        }catch(Throwable t){ready=false;update();Log.e("StatusRing","Cannot read subscriptions; stock icons retained",t);}
    }
    public static int miuiLevel(SignalStrength strength){
        if(strength==null)return -1;
        try{
            Method m=strength.getClass().getMethod("getMiuiLevel");
            Object value=m.invoke(strength);
            if(value instanceof Number)return SignalModel.normalizeLevel(((Number)value).intValue());
        }catch(ReflectiveOperationException|SecurityException ignored){}
        return SignalModel.normalizeLevel(strength.getLevel());
    }
    private static boolean inService(ServiceState state){
        int voice=state.getState();boolean packet=false;
        if(voice==ServiceState.STATE_POWER_OFF)return false;
        try{for(NetworkRegistrationInfo info:state.getNetworkRegistrationInfoList()){
            if(info.getTransportType()==AccessNetworkConstants.TRANSPORT_TYPE_WWAN&&(info.getDomain()&NetworkRegistrationInfo.DOMAIN_PS)!=0&&info.isRegistered()){packet=true;break;}
        }}catch(RuntimeException|LinkageError ignored){}
        return SignalModel.serviceAvailable(voice,packet);
    }
    private final class CellCallback extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener,TelephonyCallback.ServiceStateListener,TelephonyCallback.DisplayInfoListener,TelephonyCallback.ActiveDataSubscriptionIdListener {
        final int slot,epoch;final TelephonyManager manager;
        int network=0,voiceNetwork=0,displayNetwork=0,override=0;boolean inService=true;
        CellCallback(int s,TelephonyManager m){slot=s;manager=m;epoch=generation;}
        void sample(){
            try{ServiceState state=manager.getServiceState();if(state!=null)inService=inService(state);}catch(Throwable ignored){}
            sampleBaseState();
        }
        void sampleBaseState(){
            try{SignalStrength strength=manager.getSignalStrength();model.levels[slot]=miuiLevel(strength);}catch(Throwable ignored){}
            try{network=manager.getDataNetworkType();}catch(Throwable ignored){}
            try{voiceNetwork=manager.getVoiceNetworkType();}catch(Throwable ignored){}
            if(!inService)model.levels[slot]=0;
            updateType();
        }
        void updateType(){
            // Each SIM uses its own display-info callback; standby data UNKNOWN can
            // fall back to that SIM's voice registration without copying the data SIM.
            int base=displayNetwork!=0?displayNetwork:network!=0?network:voiceNetwork;
            model.types[slot]=inService?SignalModel.networkName(base,override):"";
        }
        @Override public void onSignalStrengthsChanged(SignalStrength s){if(epoch!=generation)return;model.levels[slot]=inService?miuiLevel(s):0;update();}
        @Override public void onServiceStateChanged(ServiceState s){if(epoch!=generation)return;inService=inService(s);if(!inService){displayNetwork=0;override=0;}sampleBaseState();update();}
        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo i){if(epoch!=generation)return;displayNetwork=i.getNetworkType();override=i.getOverrideNetworkType();updateType();update();}
        @Override public void onActiveDataSubscriptionIdChanged(int id){if(epoch!=generation)return;refreshDataSlot();update();}
    }
    private static final class Registration{
        final int id,slot;final TelephonyManager manager;final CellCallback callback;
        Registration(int i,int s,TelephonyManager m,CellCallback c){id=i;slot=s;manager=m;callback=c;}
    }
}
