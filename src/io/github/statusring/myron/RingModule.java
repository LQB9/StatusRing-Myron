package io.github.statusring.myron;

import android.content.*;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import io.github.libxposed.api.*;

public final class RingModule extends XposedModule {
    private static final String PREFIX="com.android.systemui.statusbar.";
    private static final Map<Class<?>,Map<String,Field>> FIELD_CACHE=new ConcurrentHashMap<>();
    private final IdentityHashMap<View,Session> sessions=new IdentityHashMap<>();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<XposedInterface.HookHandle> handles=new ArrayList<>();
    private Config config=new Config();
    private SharedPreferences prefs;
    private SharedPreferences.OnSharedPreferenceChangeListener preferenceListener;
    private SignalStore store;
    private Method measured;
    private Method islandChanged;
    private SystemUiContracts contracts;
    private final Set<Method> hookedMethods=new HashSet<>();
    private final Map<Class<?>,Boolean> signalSupport=new HashMap<>();
    private final Set<Class<?>> observedContainers=new HashSet<>();
    private final Map<Class<?>,String> signalKinds=new HashMap<>();
    private final Map<Class<?>,Method> slotMethods=new HashMap<>();
    private String adapterState="等待 SystemUI 接口检测",lastReport="";
    private boolean fontHook,reportPending;
    private Context appContext;
    private int reportAttempts;
    private boolean installed;
    private long lastRestartRequest;
    private boolean restartPending;

    @Override public void onPackageReady(XposedModuleInterface.PackageReadyParam param){
        if(installed||!"com.android.systemui".equals(param.getPackageName()))return;
        try{
            // Settings and manual restart also work when no drawing adapter matches.
            if(prefs==null){
                prefs=getRemotePreferences("config");config=Config.read(prefs);
                lastRestartRequest=prefs.getLong("restartRequest",0);
                preferenceListener=(p,key)->main.post(()->{
                    if("restartRequest".equals(key)){requestRestart();return;}
                    config=Config.read(prefs);refreshAll();
                });
                prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
            }
            ClassLoader loader=param.getClassLoader();
            contracts=SystemUiContracts.inspect(loader);
            if(!contracts.coreReady()||!ViewGroup.class.isAssignableFrom(contracts.battery)){
                adapterState="不支持：缺少电池宿主接口（"+contracts.missingCore()+"）；保留原生图标";scheduleReport();return;
            }
            Class<?> battery=contracts.battery;
            measured=View.class.getDeclaredMethod("setMeasuredDimension",int.class,int.class);measured.setAccessible(true);
            // Only the battery host is atomic. Optional features below cannot undo it.
            add(contracts.attach,chain->{Object result=chain.proceed();if(battery.isInstance(chain.getThisObject()))try{attach((ViewGroup)chain.getThisObject());}catch(Throwable t){failure("attach",t);}return result;});
            add(contracts.detach,chain->{Session session=sessions.remove(chain.getThisObject());if(session!=null)session.restore();return chain.proceed();});
            add(contracts.measure,chain->{
                Session session=sessions.get(chain.getThisObject());
                if(session!=null&&session.active()){
                    try{session.prepare();int h=session.dp(config.size);
                        int mode=View.MeasureSpec.getMode((int)chain.getArg(1)),limit=View.MeasureSpec.getSize((int)chain.getArg(1));
                        if(mode!=View.MeasureSpec.UNSPECIFIED&&limit>0&&h>limit)h=limit;
                        session.syncTypography(h);
                        SignalModel snapshot=session.snapshot();
                        int w=Math.round(h*session.painter.logicalWidth(config,snapshot,session.ring.position)/132f)+session.dp(2);
                        int wMode=View.MeasureSpec.getMode((int)chain.getArg(0)),wLimit=View.MeasureSpec.getSize((int)chain.getArg(0));
                        if(wMode!=View.MeasureSpec.UNSPECIFIED&&wLimit>0)w=Math.min(w,wLimit);
                        session.logTypography(w,h,snapshot);
                        measured.invoke(session.battery,w,h);session.ring.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));return null;
                    }catch(Throwable t){session.failed=true;session.restore();failure("measure",t);scheduleReport();}
                }else if(session!=null)session.restoreVisuals();
                return chain.proceed();
            });
            add(contracts.layout,chain->{Session session=sessions.get(chain.getThisObject());if(session!=null&&session.active()){
                try{session.ring.layout(0,0,session.battery.getMeasuredWidth(),session.battery.getMeasuredHeight());session.prepare();return null;}catch(Throwable t){session.failed=true;session.restore();failure("layout",t);scheduleReport();}
            }return chain.proceed();});
            installed=true;adapterState="MIUI / HyperOS 电池接口可用；等待状态栏宿主";
            for(Method networkResources:contracts.fontUpdates)fontHook|=optional(networkResources,chain->{Object result=chain.proceed();main.post(this::refreshAll);return result;},"网速字体更新");
            if(contracts.island!=null&&optional(contracts.island,chain->{
                Session s=sessions.get(chain.getThisObject());
                if(s==null||s.applyingIslandState)return chain.proceed();
                boolean requested=(boolean)chain.getArg(0),trigger=(boolean)chain.getArg(1);
                s.rememberIsland(requested,trigger);
                if(s.canKeepBattery()){
                    s.islandOverridden=true;
                    Object result=chain.proceed(new Object[]{false,false});
                    s.changed();
                    if(requested)log(Log.INFO,"StatusRing","Retained charging-island host="+s.tag+" active="+s.active()+" alpha="+s.battery.getAlpha());
                    return result;
                }
                s.islandOverridden=false;return chain.proceed();
            },"充电岛保留"))islandChanged=contracts.island;
            // Compose and classic views are also discovered from each live icon tree.
            for(String name:new String[]{PREFIX+"pipeline.shared.ui.view.ModernStatusBarView",PREFIX+"pipeline.mobile.ui.view.ModernStatusBarMobileView",PREFIX+"pipeline.wifi.ui.view.ModernStatusBarWifiView"}){
                Class<?> cls=SystemUiContracts.type(loader,name);if(cls!=null)installSignalHooks(cls);
            }
            for(String name:new String[]{"onBatteryLevelChanged","onChargeStateChanged","onDarkChangedInternal","onLightDarkTintChanged","onDarkChanged","updateState","updateVisibility$1$1","onUiModeChanged"}){
                for(Method m:SystemUiContracts.declaredMethods(battery))if(m.getName().equals(name))optional(m,chain->{Object result=chain.proceed();Session s=sessions.get(chain.getThisObject());if(s!=null){s.captureNativeDescription();s.ring.invalidate();s.updateDescription();}return result;},name);
            }
            // No charging-island animation is applied to our replacement children.
            optional(SystemUiContracts.method(battery,"enableAnimation"),chain->{Session s=sessions.get(chain.getThisObject());return s!=null&&s.islandOverridden&&s.active()?false:chain.proceed();},"充电岛动画");
            // Prevent inlined stock visibility decisions from bypassing the scoped hook.
            try{Method m=SystemUiContracts.method(contracts.icons,"onMeasure",int.class,int.class);if(m!=null)deoptimize(m);}catch(Throwable t){failure("optional icon measure optimization",t);}
            try{Method m=SystemUiContracts.method(contracts.icons,"onLayout",boolean.class,int.class,int.class,int.class,int.class);if(m!=null)deoptimize(m);}catch(Throwable t){failure("optional icon layout optimization",t);}
            log(Log.INFO,"StatusRing","Installed capability-based MIUI/HyperOS adapter; version "+CompatibilityProvider.VERSION);scheduleReport();
        }catch(Throwable t){
            if(!installed){for(XposedInterface.HookHandle h:handles)try{h.unhook();}catch(Throwable ignored){}handles.clear();hookedMethods.clear();
                adapterState="电池宿主安装失败，保留原生图标："+t.getClass().getSimpleName();
            }
            failure(installed?"optional feature skipped":"core; stock retained",t);scheduleReport();
        }
    }
    private void add(Method m,XposedInterface.Hooker callback){m.setAccessible(true);handles.add(hook(m).intercept(callback));hookedMethods.add(m);}
    private boolean optional(Method method,XposedInterface.Hooker callback,String feature){
        if(method==null)return false;if(hookedMethods.contains(method))return true;
        try{add(method,callback);return true;}catch(Throwable t){failure("skip "+feature,t);return false;}
    }
    private boolean installSignalHooks(Class<?> cls){
        Boolean ready=signalSupport.get(cls);if(ready!=null)return ready;
        Method visible=SystemUiContracts.method(cls,"isIconVisible");
        boolean installed=visible!=null&&visible.getReturnType()==boolean.class&&optional(visible,chain->{
            Session s=owner(chain.getThisObject());return s!=null&&s.active()?false:chain.proceed();
        },"信号可见性");
        Method state=SystemUiContracts.method(cls,"setVisibleState",int.class,boolean.class);
        if(installed&&state!=null&&state.getReturnType()==void.class)optional(state,chain->{Session s=owner(chain.getThisObject());return s!=null&&s.active()?chain.proceed(new Object[]{2,false}):chain.proceed();},"信号显示状态");
        signalSupport.put(cls,installed);return installed;
    }
    private void scheduleReport(){if(reportPending)return;reportPending=true;main.postDelayed(()->{reportPending=false;publishReport();},500);}
    private void publishReport(){
        try{
            if(appContext==null)appContext=(Context)Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
            if(appContext==null){if(reportAttempts++<8)scheduleReport();return;}
            String ui=appContext.getPackageManager().getPackageInfo("com.android.systemui",0).versionName;
            StringBuilder result=new StringBuilder("StatusRing "+CompatibilityProvider.VERSION+"\n")
                .append(Build.MANUFACTURER).append(" / ").append(Build.MODEL).append(" (").append(Build.DEVICE).append(")\n")
                .append("Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT).append("\nSystemUI ").append(ui).append("\n\n")
                .append(adapterState).append("\n")
                .append("验证范围：").append("myron".equals(Build.DEVICE)&&Build.VERSION.SDK_INT==36&&"16.03.251211.r".equals(ui)?"当前基础组合已核对；详见仓库验证记录":"接口探测模式，尚无此组合的实机验证").append("\n");
            if(contracts!=null){
                result.append("电池绘制：").append(installed?"宿主钩子已安装":"保留原生").append("\n")
                    .append("原生快充标志：").append(contracts.fastCharging?"可读取":"缺失，仅显示普通充电").append("\n")
                    .append("网速字体：").append(contracts.font?"可读取":"使用默认字体").append(fontHook?"，支持资源更新":"，布局时重新读取").append("\n")
                    .append("充电岛：").append(islandChanged!=null?"使用已检测的保留路径":"随原生显隐，缺失时不阻止基本绘制").append("\n");
            }
            result.append("蜂窝读数：").append(store!=null&&store.ready&&store.model.signalsKnown()?"可用，按物理卡槽分别读取":"不可用／等待，保留原生信号").append("\n")
                .append("Wi-Fi 读数：").append(store!=null&&store.wifiKnown?"可用":"不可用／等待，保留原生 Wi-Fi").append("\n");
            for(Session session:sessions.values())result.append("宿主 ").append(session.tag).append(": ").append(session.failed?"故障，已恢复原生":session.active()?"已启用":"当前隐藏／选项关闭")
                .append("；蜂窝 ").append(session.active()&&session.renderMobile?"接管":"原生").append("；Wi-Fi ").append(session.active()&&session.renderWifi?"接管":"原生").append("\n");
            String report=result.toString();if(report.equals(lastReport))return;
            Bundle bundle=new Bundle();bundle.putString("report",report);bundle.putString("version",CompatibilityProvider.VERSION);
            bundle.putInt("boot",android.provider.Settings.Global.getInt(appContext.getContentResolver(),"boot_count",-1));
            long identity=Binder.clearCallingIdentity();
            try{Bundle reply=appContext.getContentResolver().call(CompatibilityProvider.URI,"report",null,bundle);if(reply!=null&&reply.getBoolean("accepted"))lastReport=report;}
            finally{Binder.restoreCallingIdentity(identity);}
        }catch(Throwable t){if(reportAttempts++<3){failure("optional compatibility report",t);scheduleReport();}}
    }
    private void requestRestart(){
        long token=prefs.getLong("restartRequest",0),age=System.currentTimeMillis()-token;
        if(token<=lastRestartRequest)return;
        lastRestartRequest=token;
        if(restartPending||age<0||age>15000||!"com.android.systemui".equals(android.app.Application.getProcessName()))return;
        restartPending=true;log(Log.INFO,"StatusRing","Manual SystemUI restart requested from module settings");
        main.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),700);
    }
    private void failure(String where,Throwable t){log(Log.ERROR,"StatusRing",where,t);}
    private static Field field(Class<?> c,String name)throws Exception{
        Map<String,Field> cached=FIELD_CACHE.computeIfAbsent(c,key->new ConcurrentHashMap<>());Field found=cached.get(name);if(found!=null)return found;
        Class<?> owner=c;while(owner!=null){try{Field f=owner.getDeclaredField(name);f.setAccessible(true);cached.put(name,f);return f;}catch(NoSuchFieldException e){owner=owner.getSuperclass();}}
        throw new NoSuchFieldException(name);
    }
    private static Object get(Object o,String name)throws Exception{return field(o.getClass(),name).get(o);}
    private static int number(Object o,String name,int fallback){try{return ((Number)get(o,name)).intValue();}catch(Exception e){return fallback;}}
    private static boolean flag(Object o,String name){try{return Boolean.TRUE.equals(get(o,name));}catch(Exception e){return false;}}
    private void attach(ViewGroup battery)throws Exception{
        if(sessions.containsKey(battery)||!(battery.getParent() instanceof ViewGroup))return;
        ViewGroup parent=null;
        for(ViewParent p=battery.getParent();p instanceof View;p=p.getParent())
            if(p instanceof ViewGroup&&p.getClass().getSimpleName().equals("MiuiStatusBatteryContainer")){parent=(ViewGroup)p;break;}
        if(parent==null)return;
        int tag=number(battery,"mLayoutFromTag",-1);
        if(tag==-1)for(ViewParent p=parent;p instanceof View;p=p.getParent()){
            String name=p.getClass().getSimpleName();
            if(name.equals("KeyguardStatusBarView")){tag=1;break;}
            if(name.equals("MiuiPhoneStatusBarView")||name.equals("PhoneStatusBarView")){tag=0;break;}
        }
        if(tag!=0&&tag!=1&&tag!=5&&tag!=6)return;
        appContext=battery.getContext().getApplicationContext();
        if(store==null){store=new SignalStore(battery.getContext(),this::refreshAll);store.start();}
        Session session=new Session(battery,parent,tag);sessions.put(battery,session);session.changed();
        if(observedContainers.add(parent.getClass())){
            Method hide=SystemUiContracts.method(parent.getClass(),"setIsHideBattery",Boolean.class);
            if(hide==null)hide=SystemUiContracts.method(parent.getClass(),"setIsHideBattery",boolean.class);
            optional(hide,chain->{Object result=chain.proceed();for(Session s:new ArrayList<>(sessions.values()))if(s.host==chain.getThisObject()){
                s.containerHidden=Boolean.TRUE.equals(chain.getArg(0));s.changed();
            }return result;},"充电岛显隐观察");
        }
        adapterState="MIUI / HyperOS 适配器已连接状态栏";scheduleReport();
        log(Log.INFO,"StatusRing","Attached host "+tag+"; slots="+store.model.presentCount());
    }
    private String kind(View view){
        String found=signalKinds.get(view.getClass());
        // The slot is an instance property: never cache mobile/wifi by class.
        if(found==null){Method slot=SystemUiContracts.method(view.getClass(),"getSlot");found=slot==null?SystemUiContracts.signalKind(view):"@slot";signalKinds.put(view.getClass(),found);if(slot!=null)slotMethods.put(view.getClass(),slot);}
        if(!"@slot".equals(found))return found;
        try{Object slot=slotMethods.get(view.getClass()).invoke(view);return slot instanceof String?SystemUiContracts.managedSlot((String)slot):"";}catch(Throwable ignored){return "";}
    }
    private Session owner(Object object){
        if(!(object instanceof View))return null;
        View view=(View)object;String kind=kind(view);if(kind.isEmpty())return null;
        for(ViewParent parent=view.getParent();parent instanceof View;parent=parent.getParent())
            for(Session s:sessions.values())if(s.host==parent&&s.nativeSignals.contains(view))
                return (kind.equals("mobile")?s.renderMobile:s.renderWifi)?s:null;
        return null;
    }
    private void refreshAll(){
        if(!Looper.getMainLooper().isCurrentThread()){main.post(this::refreshAll);return;}
        for(Session s:new ArrayList<>(sessions.values()))s.changed();scheduleReport();
    }
    private final class Session{
        final ViewGroup battery,host;final int tag;final RingView ring;final RingPainter painter=new RingPainter();
        final IdentityHashMap<View,Float> alphas=new IdentityHashMap<>();
        final IdentityHashMap<View,Float> stockTransitionAlphas=new IdentityHashMap<>();
        final List<View> nativeSignals=new ArrayList<>();
        final ViewTreeObserver.OnPreDrawListener beforeDraw=()->{
            try{synchronizeIsland();if(active()){prepare();hideNativeSignals();}else restoreVisuals();auditIslandFrame();}
            catch(Throwable t){failed=true;restoreVisuals();failure("pre-draw; stock restored",t);scheduleReport();}
            return true;
        };
        CharSequence nativeDescription,customDescription;boolean failed;int lastChargeState=-1;
        boolean requestedIsland,requestedIslandTrigger,islandOverridden,applyingIslandState;
        boolean renderMobile,renderWifi,containerHidden;
        int islandFrames,islandInterruptions;
        TextView networkText;float networkTextPx;String lastTypography="";
        Session(ViewGroup b,ViewGroup h,int t){battery=b;host=h;tag=t;nativeDescription=b.getContentDescription();ring=new RingView(b.getContext(),this);b.addView(ring,new ViewGroup.LayoutParams(1,1));host.getViewTreeObserver().addOnPreDrawListener(beforeDraw);}
        int dp(float n){return Math.round(n*battery.getResources().getDisplayMetrics().density);}
        void collect(View root,List<View> result){result.add(root);if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),result);}}
        void scanSignals(){
            List<View> nodes=new ArrayList<>();
            Object icons=null;try{icons=get(host,"mStatusIcon");}catch(Exception ignored){}
            if(icons instanceof View)collect((View)icons,nodes);
            else{List<View> candidates=new ArrayList<>();collect(host,candidates);for(View v:candidates)if(v.getClass().getSimpleName().equals("MiuiStatusIconContainer")){collect(v,nodes);break;}}
            nativeSignals.clear();boolean mobileFound=false,wifiFound=false,mobileSupported=true,wifiSupported=true;
            for(View v:nodes){String kind=kind(v);if(kind.isEmpty())continue;
                nativeSignals.add(v);boolean supported=installSignalHooks(v.getClass());
                if(kind.equals("mobile")){mobileFound=true;mobileSupported&=supported;}else{wifiFound=true;wifiSupported&=supported;}
            }
            boolean mobile=mobileFound&&mobileSupported&&store!=null&&store.ready&&store.model.signalsKnown();
            boolean wifi=wifiFound&&wifiSupported&&store!=null&&store.wifiKnown;
            if(mobile!=renderMobile||wifi!=renderWifi){renderMobile=mobile;renderWifi=wifi;battery.requestLayout();host.requestLayout();scheduleReport();}
        }
        void syncTypography(int height){
            if(networkText==null||!networkText.isAttachedToWindow()){
                networkText=null;
                List<View> nodes=new ArrayList<>();collect(host,nodes);
                for(View icon:nodes)if(icon.getClass().getSimpleName().equals("NetworkSpeedView"))try{Object text=get(icon,"mNetworkSpeedNumberText");if(text instanceof TextView){networkText=(TextView)text;break;}}catch(Exception ignored){}
            }
            if(networkText!=null&&height>0){networkTextPx=networkText.getTextSize();painter.setLabelStyle(networkTextPx*132f/height,networkText.getTypeface());}
        }
        void logTypography(int width,int height,SignalModel snapshot){
            if(networkTextPx<=0)return;
            float drawn=painter.labelTextSize()*Math.min(width/painter.logicalWidth(config,snapshot,ring.position),height/132f);
            String value=String.format(Locale.US,"host=%d network=%.2fpx card/RAT=%.2fpx",tag,networkTextPx,drawn);
            if(!value.equals(lastTypography)){lastTypography=value;log(Log.INFO,"StatusRing","Typography "+value);}
        }
        boolean canKeepBattery(){
            if(failed||store==null||!config.accepts(tag)||flag(battery,"mToAod"))return false;
            if(flag(battery,"mHomeBlock")||flag(battery,"mMinimalism"))return false;
            try{String privacy=String.valueOf(get(host,"mPrivacyState"));if(privacy.contains("SHOW_PRIVACY"))return false;}catch(Exception ignored){}
            return true;
        }
        boolean active(){
            if(!canKeepBattery())return false;
            if(containerHidden||flag(host,"mIsHideBattery")||flag(battery,"mIsAddBatteryIsland"))return false;
            if(battery.getVisibility()!=View.VISIBLE)return false;
            return true;
        }
        void rememberIsland(boolean requested,boolean trigger){
            if(requested&&!requestedIsland){
                islandFrames=0;islandInterruptions=0;
                main.postDelayed(()->log(Log.INFO,"StatusRing","Charging-island frames host="+tag+" checked="+islandFrames+" interruptions="+islandInterruptions),3500);
            }else if(!requested&&requestedIsland)log(Log.INFO,"StatusRing","Charging-island ended host="+tag+" checked="+islandFrames+" interruptions="+islandInterruptions);
            requestedIsland=requested;requestedIslandTrigger=trigger;
        }
        void applyIslandState(boolean requested,boolean trigger){
            applyingIslandState=true;
            try{islandChanged.invoke(battery,requested,trigger);}
            catch(Throwable t){failed=true;failure("island handoff; stock restored",t);}
            finally{applyingIslandState=false;}
        }
        void synchronizeIsland(){
            if(islandChanged==null)return;
            boolean keep=canKeepBattery();
            if(keep&&!islandOverridden){
                rememberIsland(flag(battery,"mStoreIsAddBatteryIsland"),flag(battery,"mByIsAddBatteryIslandTrigger"));
                islandOverridden=true;applyIslandState(false,false);
            }else if(!keep&&islandOverridden)restoreIslandState();
        }
        void restoreIslandState(){if(islandOverridden){islandOverridden=false;restoreVisuals();applyIslandState(requestedIsland,requestedIslandTrigger);}}
        void auditIslandFrame(){
            if(!requestedIsland||!islandOverridden||!canKeepBattery()||!host.isShown())return;
            islandFrames++;
            boolean intact=active()&&ring.getVisibility()==View.VISIBLE&&battery.getAlpha()>.99f&&battery.getTransitionAlpha()>.99f&&Math.abs(battery.getScaleX()-1)<.01f&&Math.abs(battery.getScaleY()-1)<.01f&&Math.abs(battery.getTranslationX())<.5f;
            for(View child:alphas.keySet())if(child.getAlpha()!=0)intact=false;
            for(View signal:stockTransitionAlphas.keySet())if(signal.getTransitionAlpha()!=0)intact=false;
            if(!intact){islandInterruptions++;if(islandInterruptions==1)log(Log.ERROR,"StatusRing","Charging-island display interrupted host="+tag+" active="+active()+" alpha="+battery.getAlpha());}
        }
        void prepare(){
            scanSignals();
            captureNativeDescription();
            ring.setVisibility(View.VISIBLE);
            ring.syncBatteryPosition(snapshot());
            for(int i=0;i<battery.getChildCount();i++){View child=battery.getChildAt(i);if(child!=ring){if(!alphas.containsKey(child))alphas.put(child,child.getAlpha());if(child.getAlpha()!=0)child.setAlpha(0);}}
        }
        void hideNativeSignals(){
            // HyperOS's Folme container writes normal alpha AFTER setVisibleState.
            // A separate transition multiplier suppresses those stale labels without
            // changing the animator's state. Only supported signal slots inside
            // this session's own statusIcons container are affected.
            Iterator<Map.Entry<View,Float>> iterator=stockTransitionAlphas.entrySet().iterator();
            while(iterator.hasNext()){Map.Entry<View,Float> e=iterator.next();if(owner(e.getKey())!=this){e.getKey().setTransitionAlpha(e.getValue());iterator.remove();}}
                for(View signal:nativeSignals){if(owner(signal)!=this)continue;
                    if(!stockTransitionAlphas.containsKey(signal))stockTransitionAlphas.put(signal,signal.getTransitionAlpha());
                    if(signal.getTransitionAlpha()!=0)signal.setTransitionAlpha(0);
                }
        }
        void restoreVisuals(){
            for(Map.Entry<View,Float> e:alphas.entrySet())e.getKey().setAlpha(e.getValue());alphas.clear();
            for(Map.Entry<View,Float> e:stockTransitionAlphas.entrySet())e.getKey().setTransitionAlpha(e.getValue());stockTransitionAlphas.clear();
            ring.stopMotion();ring.setVisibility(View.GONE);
            if(customDescription!=null){battery.setContentDescription(nativeDescription);customDescription=null;}
        }
        void restore(){if(host.getViewTreeObserver().isAlive())host.getViewTreeObserver().removeOnPreDrawListener(beforeDraw);restoreIslandState();restoreVisuals();battery.removeView(ring);host.requestLayout();}
        void captureNativeDescription(){CharSequence current=battery.getContentDescription();if(customDescription==null||!android.text.TextUtils.equals(current,customDescription))nativeDescription=current;}
        void updateDescription(){if(active()){captureNativeDescription();customDescription=snapshot().describe();battery.setContentDescription(customDescription);}}
        SignalModel snapshot(){
            SignalModel m=store.model.copy();m.battery=number(battery,"mLevel",m.battery);
            if(contracts.charging)m.charging=flag(battery,"mCharging");
            if(!renderMobile){Arrays.fill(m.levels,-2);Arrays.fill(m.types,"");m.dataSlot=-1;}
            if(!renderWifi){m.wifi=false;m.wifiLevel=0;}
            m.fastCharging=m.charging&&flag(battery,"mQuickCharging");
            int state=m.chargeBoltCount();if(state!=lastChargeState){lastChargeState=state;log(Log.INFO,"StatusRing","Charge host "+tag+": charging="+m.charging+", fast="+m.fastCharging+", bolts="+state);}
            return m;
        }
        int color(){
            try{TextView text=(TextView)get(battery,"mBatteryPercentView");return text.getCurrentTextColor();}catch(Exception ignored){}
            if(flag(battery,"mUseTint"))return number(battery,"mTintColor",0xffffffff);
            return number(battery,flag(battery,"mDark")?"mDarkColor":"mLightColor",0xffffffff);
        }
        void changed(){synchronizeIsland();if(active()){prepare();updateDescription();}else restoreVisuals();ring.invalidate();battery.requestLayout();for(int i=0;i<host.getChildCount();i++)host.getChildAt(i).requestLayout();host.requestLayout();host.invalidate();}
    }
    private final class RingView extends View{
        final Session session;
        final BatteryMotion motion=new BatteryMotion();
        float position;boolean framePosted;int lastStyle=-1;
        final Runnable motionFrame=this::advanceMotion;
        void advanceMotion(){
            framePosted=false;
            if(!isAttachedToWindow()||!isShown()||!session.active()){stopMotion();return;}
            long now=SystemClock.uptimeMillis();position=motion.valueAt(now);
            invalidate();session.battery.requestLayout();session.host.requestLayout();
            if(motion.isRunning(now))postFrame();
        }
        RingView(Context context,Session s){super(context);session=s;setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);setClickable(false);}
        void postFrame(){if(!framePosted){framePosted=true;postOnAnimation(motionFrame);}}
        void syncBatteryPosition(SignalModel snapshot){
            long now=SystemClock.uptimeMillis();
            boolean animate=lastStyle==config.style&&isAttachedToWindow()&&isLaidOut()&&isShown()&&ValueAnimator.areAnimatorsEnabled();
            lastStyle=config.style;
            if(motion.setTarget(session.painter.externalPercent(config,snapshot),now,animate)){
                position=motion.valueAt(now);invalidate();
                if(motion.isRunning(now))postFrame();
                else{removeCallbacks(motionFrame);framePosted=false;}
            }
        }
        void stopMotion(){removeCallbacks(motionFrame);framePosted=false;motion.snap();position=motion.valueAt(SystemClock.uptimeMillis());lastStyle=-1;}
        @Override protected void onDetachedFromWindow(){stopMotion();motion.reset();super.onDetachedFromWindow();}
        @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE&&motion!=null)stopMotion();}
        @Override protected void onDraw(Canvas canvas){
            if(!session.active())return;
            try{session.painter.draw(canvas,getWidth(),getHeight(),session.snapshot(),config,session.color(),position);}
            catch(Throwable t){session.failed=true;main.post(session::changed);failure("drawing; stock restored",t);}
        }
    }
}
