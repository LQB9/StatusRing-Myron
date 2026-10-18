package io.github.statusring.myron;

import android.content.SharedPreferences;

public final class Config {
    public boolean enabled=true, lockscreen=true, panels=false, colors=true, percent=false, networkType=false;
    public int style=1, size=22, weight=100, batteryText=36, rightBatteryText=46;
    public static Config read(SharedPreferences p) {
        Config c=new Config();
        if(p==null)return c;
        c.enabled=p.getBoolean("enabled",true);
        c.lockscreen=p.getBoolean("lockscreen",true);
        c.panels=p.getBoolean("panels",false);
        c.colors=p.getBoolean("colors",true);
        c.percent=p.getBoolean("percent",false);
        c.networkType=p.getBoolean("networkType",false);
        c.style=clamp(p.getInt("style",1),0,2);
        c.size=clamp(p.getInt("size",22),16,28);
        c.weight=clamp(p.getInt("weight",100),75,140);
        c.batteryText=clamp(p.getInt("batteryText",36),26,40);
        c.rightBatteryText=clamp(p.getInt("rightBatteryText",46),36,56);
        return c;
    }
    public void write(SharedPreferences p) {
        p.edit().putBoolean("enabled",enabled).putBoolean("lockscreen",lockscreen).putBoolean("panels",panels)
            .putBoolean("colors",colors).putBoolean("percent",percent).putBoolean("networkType",networkType)
            .putInt("style",style).putInt("size",size).putInt("weight",weight).putInt("batteryText",batteryText)
            .putInt("rightBatteryText",rightBatteryText).commit();
    }
    public boolean accepts(int tag) {return enabled&&(tag==0||(tag==1&&lockscreen)||((tag==5||tag==6)&&panels));}
    public static int clamp(int x,int min,int max){return Math.max(min,Math.min(max,x));}
}
