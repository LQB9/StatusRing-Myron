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
    private boolean installed;
    private long lastRestartRequest;
    private boolean restartPending;

    @Override public void onPackageReady(XposedModuleInterface.PackageReadyParam param){
        if(installed||!"com.android.systemui".equals(param.getPackageName()))return;
        // These hooks are verified against the supplied myron/Android 16 SystemUI.
        if(Build.VERSION.SDK_INT!=36||!"myron".equals(Build.DEVICE)){log(Log.WARN,"StatusRing","Different device/Android version; stock SystemUI retained");return;}
        try{
            ClassLoader loader=param.getClassLoader();
            Class<?> battery=Class.forName(PREFIX+"views.MiuiBatteryMeterView",false,loader);
            Class<?> icons=Class.forName(PREFIX+"views.MiuiStatusIconContainer",false,loader);
            Class<?> modern=Class.forName(PREFIX+"pipeline.shared.ui.view.ModernStatusBarView",false,loader);
            Class<?> networkSpeed=Class.forName(PREFIX+"views.NetworkSpeedView",false,loader);
            field(networkSpeed,"mNetworkSpeedNumberText");
            Method networkResources=method(networkSpeed,"updateResources$4");
            measured=View.class.getDeclaredMethod("setMeasuredDimension",int.class,int.class);measured.setAccessible(true);
            // Resolve required contracts before installing any hook.
            field(battery,"mLevel");field(battery,"mCharging");field(battery,"mQuickCharging");field(battery,"mLayoutFromTag");field(battery,"mBatteryPercentView");
            Method attach=method(battery,"onAttachedToWindow"),detach=method(battery,"onDetachedFromWindow");
            Method measure=method(battery,"onMeasure",int.class,int.class);
            Method layout=method(battery,"onLayout",boolean.class,int.class,int.class,int.class,int.class);
            Method visible=method(modern,"isIconVisible");
            islandChanged=method(battery,"updateIslandChanged",boolean.class,boolean.class);
            for(String name:new String[]{"mStoreIsAddBatteryIsland","mByIsAddBatteryIslandTrigger","mHomeBlock","mMinimalism"})field(battery,name);
            prefs=getRemotePreferences("config");config=Config.read(prefs);
            lastRestartRequest=prefs.getLong("restartRequest",0);
            preferenceListener=(p,key)->main.post(()->{
                if("restartRequest".equals(key)){requestRestart();return;}
                config=Config.read(prefs);refreshAll();
            });
            prefs.registerOnSharedPreferenceChangeListener(preferenceListener);
            add(networkResources,chain->{Object result=chain.proceed();main.post(this::refreshAll);return result;});
            add(islandChanged,chain->{
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
            });
            add(attach,chain->{Object result=chain.proceed();try{attach((ViewGroup)chain.getThisObject());}catch(Throwable t){failure("attach",t);}return result;});
            add(detach,chain->{Session session=sessions.remove(chain.getThisObject());if(session!=null)session.restore();return chain.proceed();});
            add(measure,chain->{
                Session session=sessions.get(chain.getThisObject());
                if(session!=null&&session.active()){
                    try{session.prepare();int h=session.dp(config.size);
                        // Parent height is an upper bound. Never draw outside the status bar window.
                        int mode=View.MeasureSpec.getMode((int)chain.getArg(1)),limit=View.MeasureSpec.getSize((int)chain.getArg(1));
                        if(mode!=View.MeasureSpec.UNSPECIFIED&&limit>0&&h>limit)h=limit;
                        session.syncTypography(h);
                        SignalModel snapshot=session.snapshot();
                        int w=Math.round(h*session.painter.logicalWidth(config,snapshot,session.ring.position)/132f)+session.dp(2);
                        int wMode=View.MeasureSpec.getMode((int)chain.getArg(0)),wLimit=View.MeasureSpec.getSize((int)chain.getArg(0));
                        if(wMode!=View.MeasureSpec.UNSPECIFIED&&wLimit>0)w=Math.min(w,wLimit);
                        session.logTypography(w,h,snapshot);
                        measured.invoke(session.battery,w,h);session.ring.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY));return null;
                    }catch(Throwable t){session.failed=true;session.restore();failure("measure",t);}
                }else if(session!=null)session.restoreVisuals();
                return chain.proceed();
            });
            add(layout,chain->{Session session=sessions.get(chain.getThisObject());if(session!=null&&session.active()){
                try{session.ring.layout(0,0,session.battery.getMeasuredWidth(),session.battery.getMeasuredHeight());session.prepare();return null;}catch(Throwable t){session.failed=true;session.restore();failure("layout",t);}
            }return chain.proceed();});
            add(visible,chain->{Session session=owner(chain.getThisObject());return session!=null&&session.active()?false:chain.proceed();});
            // Both classes call the base implementation; the mobile override additionally sets alpha.
            for(String name:new String[]{PREFIX+"pipeline.shared.ui.view.ModernStatusBarView",PREFIX+"pipeline.mobile.ui.view.ModernStatusBarMobileView"}){
                Class<?> cls=Class.forName(name,false,loader);
                add(method(cls,"setVisibleState",int.class,boolean.class),chain->{Session session=owner(chain.getThisObject());
                    return session!=null&&session.active()?chain.proceed(new Object[]{2,false}):chain.proceed();});
            }
            for(String name:new String[]{"onBatteryLevelChanged","onChargeStateChanged","onDarkChangedInternal","onLightDarkTintChanged","onDarkChanged","updateState","updateVisibility$1$1","onUiModeChanged"}){
                for(Method m:battery.getDeclaredMethods())if(m.getName().equals(name))add(m,chain->{Object result=chain.proceed();Session s=sessions.get(chain.getThisObject());if(s!=null){s.captureNativeDescription();s.ring.invalidate();s.updateDescription();}return result;});
            }
            // No charging-island animation is applied to our replacement children.
            add(method(battery,"enableAnimation"),chain->{Session s=sessions.get(chain.getThisObject());return s!=null&&s.active()?false:chain.proceed();});
            // Prevent inlined stock visibility decisions from bypassing the scoped hook.
            deoptimize(method(icons,"onMeasure",int.class,int.class));
            deoptimize(method(icons,"onLayout",boolean.class,int.class,int.class,int.class,int.class));
            installed=true;log(Log.INFO,"StatusRing","Installed myron/HyperOS3/API36 contracts; version 1.0.13");
        }catch(Throwable t){for(XposedInterface.HookHandle h:handles)try{h.unhook();}catch(Throwable ignored){}handles.clear();failure("contract; stock retained",t);}
    }
    private void add(Method m,XposedInterface.Hooker callback){m.setAccessible(true);handles.add(hook(m).intercept(callback));}
    private void requestRestart(){
        long token=prefs.getLong("restartRequest",0),age=System.currentTimeMillis()-token;
        if(token<=lastRestartRequest)return;
        lastRestartRequest=token;
        if(restartPending||age<0||age>15000||!"com.android.systemui".equals(android.app.Application.getProcessName()))return;
        restartPending=true;log(Log.INFO,"StatusRing","Manual SystemUI restart requested from module settings");
        main.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),700);
    }
    private void failure(String where,Throwable t){log(Log.ERROR,"StatusRing",where,t);}
    private static Method method(Class<?> c,String name,Class<?>... parameters)throws Exception{Method m=c.getDeclaredMethod(name,parameters);m.setAccessible(true);return m;}
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
        ViewGroup parent=(ViewGroup)battery.getParent();
        if(!parent.getClass().getName().equals(PREFIX+"views.MiuiStatusBatteryContainer"))return;
        int tag=number(battery,"mLayoutFromTag",-1);
        if(tag!=0&&tag!=1&&tag!=5&&tag!=6)return;
        if(store==null){store=new SignalStore(battery.getContext(),this::refreshAll);store.start();}
        Session session=new Session(battery,parent,tag);sessions.put(battery,session);session.changed();
        log(Log.INFO,"StatusRing","Attached host "+tag+"; slots="+store.model.presentCount());
    }
    private Session owner(Object object){
        if(!(object instanceof View))return null;
        View view=(View)object;String name=view.getClass().getName();
        if(!name.equals(PREFIX+"pipeline.mobile.ui.view.ModernStatusBarMobileView")&&!name.equals(PREFIX+"pipeline.wifi.ui.view.ModernStatusBarWifiView"))return null;
        if(name.equals(PREFIX+"pipeline.wifi.ui.view.ModernStatusBarWifiView")&&(store==null||!store.wifiKnown))return null;
        ViewParent parent=view.getParent();
        if(!(parent instanceof View))return null;
        Object grandparent=parent.getParent();
        for(Session s:sessions.values())if(s.host==grandparent)return s;
        return null;
    }
    private void refreshAll(){
        if(!Looper.getMainLooper().isCurrentThread()){main.post(this::refreshAll);return;}
        for(Session s:new ArrayList<>(sessions.values()))s.changed();
    }
    private final class Session{
        final ViewGroup battery,host;final int tag;final RingView ring;final RingPainter painter=new RingPainter();
        final IdentityHashMap<View,Float> alphas=new IdentityHashMap<>();
        final IdentityHashMap<View,Float> stockTransitionAlphas=new IdentityHashMap<>();
        final ViewTreeObserver.OnPreDrawListener beforeDraw=()->{
            try{synchronizeIsland();if(active()){prepare();hideNativeSignals();}else restoreVisuals();auditIslandFrame();}
            catch(Throwable t){failed=true;restoreVisuals();failure("pre-draw; stock restored",t);}
            return true;
        };
        CharSequence nativeDescription,customDescription;boolean failed;int lastChargeState=-1;
        boolean requestedIsland,requestedIslandTrigger,islandOverridden,applyingIslandState;
        int islandFrames,islandInterruptions;
        TextView networkText;float networkTextPx;String lastTypography="";
        Session(ViewGroup b,ViewGroup h,int t){battery=b;host=h;tag=t;nativeDescription=b.getContentDescription();ring=new RingView(b.getContext(),this);b.addView(ring,new ViewGroup.LayoutParams(1,1));host.getViewTreeObserver().addOnPreDrawListener(beforeDraw);}
        int dp(float n){return Math.round(n*battery.getResources().getDisplayMetrics().density);}
        void syncTypography(int height){
            if(networkText==null||!networkText.isAttachedToWindow()){
                networkText=null;
                for(int i=0;i<host.getChildCount();i++){
                    View child=host.getChildAt(i);
                    if(!(child instanceof ViewGroup)||!child.getClass().getName().equals(PREFIX+"views.MiuiStatusIconContainer"))continue;
                    ViewGroup icons=(ViewGroup)child;
                    for(int j=0;j<icons.getChildCount();j++){
                        View icon=icons.getChildAt(j);
                        if(icon.getClass().getName().equals(PREFIX+"views.NetworkSpeedView"))try{networkText=(TextView)get(icon,"mNetworkSpeedNumberText");}catch(Exception ignored){}
                    }
                }
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
            if(failed||store==null||!store.ready||!config.accepts(tag)||flag(battery,"mToAod"))return false;
            if(flag(battery,"mHomeBlock")||flag(battery,"mMinimalism"))return false;
            try{String privacy=String.valueOf(get(host,"mPrivacyState"));if(privacy.contains("SHOW_PRIVACY"))return false;}catch(Exception ignored){}
            return true;
        }
        boolean active(){
            if(!canKeepBattery())return false;
            if(flag(host,"mIsHideBattery")||flag(battery,"mIsAddBatteryIsland"))return false;
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
            captureNativeDescription();
            ring.setVisibility(View.VISIBLE);
            ring.syncBatteryPosition(snapshot());
            for(int i=0;i<battery.getChildCount();i++){View child=battery.getChildAt(i);if(child!=ring){if(!alphas.containsKey(child))alphas.put(child,child.getAlpha());if(child.getAlpha()!=0)child.setAlpha(0);}}
        }
        void hideNativeSignals(){
            // HyperOS's Folme container writes normal alpha AFTER setVisibleState.
            // A separate transition multiplier suppresses those stale labels without
            // changing the animator's state. Only the two exact signal View types
            // inside this session's own statusIcons container are affected.
            Iterator<Map.Entry<View,Float>> iterator=stockTransitionAlphas.entrySet().iterator();
            while(iterator.hasNext()){Map.Entry<View,Float> e=iterator.next();if(owner(e.getKey())!=this){e.getKey().setTransitionAlpha(e.getValue());iterator.remove();}}
            for(int i=0;i<host.getChildCount();i++){
                View child=host.getChildAt(i);
                if(!(child instanceof ViewGroup)||!child.getClass().getName().equals(PREFIX+"views.MiuiStatusIconContainer"))continue;
                ViewGroup icons=(ViewGroup)child;
                for(int j=0;j<icons.getChildCount();j++){
                    View signal=icons.getChildAt(j);if(owner(signal)!=this)continue;
                    if(!stockTransitionAlphas.containsKey(signal))stockTransitionAlphas.put(signal,signal.getTransitionAlpha());
                    if(signal.getTransitionAlpha()!=0)signal.setTransitionAlpha(0);
                }
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
            SignalModel m=store.model;m.battery=number(battery,"mLevel",m.battery);m.charging=flag(battery,"mCharging");
            m.fastCharging=m.charging&&flag(battery,"mQuickCharging");
            int state=m.chargeBoltCount();if(state!=lastChargeState){lastChargeState=state;log(Log.INFO,"StatusRing","Charge host "+tag+": charging="+m.charging+", fast="+m.fastCharging+", bolts="+state);}
            return m;
        }
        int color(){try{TextView text=(TextView)get(battery,"mBatteryPercentView");return text.getCurrentTextColor();}catch(Exception e){return 0xffffffff;}}
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
