package io.github.statusring.myron;

/** Monotonic-time motion state; re-targets from the current position on a quick replug. */
public final class BatteryMotion {
    public static final int DURATION_MS=280;
    private boolean initialized;
    private float start,end;
    private long began;
    private int duration;
    public boolean setTarget(boolean external,long now,boolean animate){
        float next=external?1:0;
        if(!initialized){initialized=true;start=end=next;duration=0;return true;}
        if(next==end){
            if(!animate&&isRunning(now)){snap();return true;}
            return false;
        }
        float current=valueAt(now);end=next;start=current;began=now;
        duration=animate?Math.round(DURATION_MS*Math.abs(end-start)):0;
        if(duration==0)start=end;
        return true;
    }
    public float valueAt(long now){
        if(duration==0)return end;
        float time=Math.max(0,Math.min(1,(now-began)/(float)duration));
        float ease=time*time*(3-2*time);
        return start+(end-start)*ease;
    }
    public boolean isRunning(long now){return initialized&&duration>0&&now<began+duration;}
    public void snap(){start=end;duration=0;}
    public void reset(){initialized=false;duration=0;}
}
