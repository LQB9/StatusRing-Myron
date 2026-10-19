package io.github.statusring.myron;

import java.lang.reflect.*;
import java.util.*;

/** Runtime contracts for the MIUI/HyperOS adapter. No device-name allowlist. */
public final class SystemUiContracts {
    public static final String PREFIX="com.android.systemui.statusbar.";
    public final Class<?> battery,icons,modern,speed;
    public final Method attach,detach,measure,layout,island;
    public final boolean fastCharging,charging,font,tag;
    public final List<Method> fontUpdates=new ArrayList<>();

    public static SystemUiContracts inspect(ClassLoader loader){
        return new SystemUiContracts(type(loader,PREFIX+"views.MiuiBatteryMeterView"),
            type(loader,PREFIX+"views.MiuiStatusIconContainer"),
            type(loader,PREFIX+"pipeline.shared.ui.view.ModernStatusBarView"),
            type(loader,PREFIX+"views.NetworkSpeedView"));
    }
    public SystemUiContracts(Class<?> b,Class<?> i,Class<?> m,Class<?> n){
        battery=b;icons=i;modern=m;speed=n;
        attach=method(b,"onAttachedToWindow");detach=method(b,"onDetachedFromWindow");
        measure=method(b,"onMeasure",int.class,int.class);
        layout=method(b,"onLayout",boolean.class,int.class,int.class,int.class,int.class);
        charging=fieldType(b,"mCharging",boolean.class);fastCharging=fieldType(b,"mQuickCharging",boolean.class);
        tag=fieldType(b,"mLayoutFromTag",int.class);
        Method candidate=method(b,"updateIslandChanged",boolean.class,boolean.class);
        island=candidate!=null&&candidate.getReturnType()==void.class
            &&fieldType(b,"mStoreIsAddBatteryIsland",boolean.class)
            &&fieldType(b,"mByIsAddBatteryIslandTrigger",boolean.class)
            &&fieldType(b,"mHomeBlock",boolean.class)&&fieldType(b,"mMinimalism",boolean.class)?candidate:null;
        font=field(n,"mNetworkSpeedNumberText")!=null;
        for(Class<?> c=n;c!=null&&!c.getName().startsWith("android.");c=c.getSuperclass())
            for(Method method:declaredMethods(c))
                if(method.getName().matches("updateResources(?:\\$\\d+)*")&&method.getParameterCount()==0
                    &&method.getReturnType()==void.class&&!Modifier.isStatic(method.getModifiers()))fontUpdates.add(method);
    }
    public boolean coreReady(){
        return fieldType(battery,"mLevel",int.class)&&vendorVoid(attach)&&vendorVoid(detach)&&vendorVoid(measure)&&vendorVoid(layout);
    }
    private static boolean vendorVoid(Method method){
        return method!=null&&method.getReturnType()==void.class&&!Modifier.isStatic(method.getModifiers())
            &&!method.getDeclaringClass().getName().startsWith("android.");
    }
    public String missingCore(){
        List<String> missing=new ArrayList<>();
        if(battery==null)missing.add("MiuiBatteryMeterView");
        if(!fieldType(battery,"mLevel",int.class))missing.add("mLevel:int");
        if(!vendorVoid(attach))missing.add("onAttachedToWindow");
        if(!vendorVoid(detach))missing.add("onDetachedFromWindow");
        if(!vendorVoid(measure))missing.add("onMeasure");
        if(!vendorVoid(layout))missing.add("onLayout");
        return String.join(", ",missing);
    }
    public static Class<?> type(ClassLoader loader,String name){try{return Class.forName(name,false,loader);}catch(Throwable ignored){return null;}}
    public static Field field(Class<?> type,String name){
        for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Field field=c.getDeclaredField(name);field.setAccessible(true);return field;}catch(Throwable ignored){}
        return null;
    }
    public static boolean fieldType(Class<?> type,String name,Class<?> expected){Field f=field(type,name);return f!=null&&f.getType()==expected;}
    public static Method method(Class<?> type,String name,Class<?>... parameters){
        for(Class<?> c=type;c!=null;c=c.getSuperclass())try{Method m=c.getDeclaredMethod(name,parameters);m.setAccessible(true);return m;}catch(Throwable ignored){}
        return null;
    }
    public static Method[] declaredMethods(Class<?> type){try{return type.getDeclaredMethods();}catch(Throwable ignored){return new Method[0];}}
    public static String signalKind(Object view){
        if(view==null)return "";
        Method slot=method(view.getClass(),"getSlot");
        if(slot!=null)try{Object value=slot.invoke(view);if(value instanceof String)return managedSlot((String)value);}catch(Throwable ignored){}
        String name=view.getClass().getSimpleName();
        if(name.equals("ModernStatusBarMobileView"))return "mobile";
        if(name.equals("ModernStatusBarWifiView"))return "wifi";
        return "";
    }
    public static String managedSlot(String slot){
        return "wifi".equals(slot)?"wifi":("mobile".equals(slot)||"stacked_mobile".equals(slot))?"mobile":"";
    }
}
