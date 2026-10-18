package io.github.statusring.myron;

import java.lang.ref.WeakReference;
import io.github.libxposed.service.*;

/** Keeps the framework binder across Activity recreation without keeping an Activity alive. */
public final class FrameworkConnection implements XposedServiceHelper.OnServiceListener {
    private static final FrameworkConnection INSTANCE=new FrameworkConnection();
    private volatile XposedService service;
    private volatile WeakReference<XposedServiceHelper.OnServiceListener> observer=new WeakReference<>(null);
    private boolean registered;
    private FrameworkConnection(){}
    public static void attach(XposedServiceHelper.OnServiceListener listener){
        INSTANCE.observer=new WeakReference<>(listener);
        if(!INSTANCE.registered){INSTANCE.registered=true;XposedServiceHelper.registerListener(INSTANCE);}
        else{XposedService current=INSTANCE.service;if(current!=null)listener.onServiceBind(current);}
    }
    public static void detach(XposedServiceHelper.OnServiceListener listener){
        if(INSTANCE.observer.get()==listener)INSTANCE.observer=new WeakReference<>(null);
    }
    @Override public void onServiceBind(XposedService current){
        service=current;XposedServiceHelper.OnServiceListener listener=observer.get();if(listener!=null)listener.onServiceBind(current);
    }
    @Override public void onServiceDied(XposedService dead){
        if(service!=dead)return;
        service=null;XposedServiceHelper.OnServiceListener listener=observer.get();if(listener!=null)listener.onServiceDied(dead);
    }
}
