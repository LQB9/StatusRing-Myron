package io.github.statusring.myron;

import android.graphics.*;

public final class RingPainter {
    private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path=new Path();
    private final RectF rect=new RectF();
    private final Rect numberBounds=new Rect();
    private final Typeface boldFace=Typeface.create("sans-serif",Typeface.BOLD);
    private final Typeface mediumFace=Typeface.create(Typeface.SANS_SERIF,500,false);
    private Typeface labelFace=mediumFace;
    private float labelSize=28,cardSpan=54,networkSpan=44,dotStart=78,networkX=182,ringX=264,externalWidth=328;
    private int ink,dim,accent;
    private float weight;
    private int measuredRightSize=-1;
    private float measuredRightSpan;
    public RingPainter(){p.setSubpixelText(true);}
    public boolean setLabelStyle(float size,Typeface face){
        if(size<=0||Float.isNaN(size)||Float.isInfinite(size))return false;
        Typeface next=face==null?mediumFace:face;
        if(Math.abs(labelSize-size)<.01f&&labelFace.equals(next))return false;
        labelSize=size;labelFace=next;p.setTypeface(labelFace);p.setTextSize(labelSize);
        cardSpan=Math.max(54,(float)Math.ceil(Math.max(p.measureText("卡1"),p.measureText("卡2"))+6));
        networkSpan=Math.max(44,(float)Math.ceil(Math.max(p.measureText("4G"),p.measureText("5G"))+6));
        dotStart=cardSpan+24;networkX=dotStart+82+networkSpan/2;ringX=networkX+networkSpan/2+60;externalWidth=ringX+64;
        return true;
    }
    public float labelTextSize(){return labelSize;}
    public float dotStart(){return dotStart;}
    public float ringCenter(){return ringX;}
    public float ringCenter(SignalModel model){return model.presentCount()==0?60:ringX;}
    public float baseWidth(Config c){return c.style==1?externalWidth:120;}
    public float baseWidth(Config c,SignalModel model){return c.style==1&&model!=null&&model.presentCount()==0?124:baseWidth(c);}
    private float networkWidth(){return Math.max(48,networkSpan);}
    private float rightOffset(Config c,boolean hasNetworkType){
        if(hasNetworkType)return 6;
        if(c.style==1)return 4.5f*c.weight/100f;
        if(c.style==0)return 2+4.5f*c.weight/100f;
        return 1+4.4f*c.weight/100f;
    }
    private float percentWidth(Config c,boolean hasNetworkType){
        if(measuredRightSize!=c.rightBatteryText){
            p.setTypeface(boldFace);p.setTextSize(c.rightBatteryText);
            measuredRightSpan=(float)Math.ceil(Math.max(p.measureText("100"),Math.max(p.measureText("99"),p.measureText("—"))));
            measuredRightSize=c.rightBatteryText;
        }
        // Actual font width plus slide/tail room; do not centre short values in a 3-digit box.
        return measuredRightSpan+rightOffset(c,hasNetworkType)+18;
    }
    private float percentWidth(Config c){return percentWidth(c,c.networkType&&c.style!=1);}
    public boolean externalPercent(Config c,SignalModel model){return c.percent||(model!=null&&model.charging);}
    public float logicalWidth(Config c){return baseWidth(c)+(c.percent?percentWidth(c):0)+(c.networkType&&c.style!=1?networkWidth():0);}
    public float logicalWidth(Config c,SignalModel model){return logicalWidth(c,model,externalPercent(c,model)?1:0);}
    public float logicalWidth(Config c,SignalModel model,float position){
        boolean extra=c.networkType&&c.style!=1&&model.presentCount()>0;
        return baseWidth(c,model)+percentWidth(c,extra)*Math.min(1,clampPosition(position)*2)+(extra?networkWidth():0);
    }
    private static float clampPosition(float position){return Math.max(0,Math.min(1,position));}
    public void draw(Canvas c,float width,float height,SignalModel s,Config cfg,int color){
        draw(c,width,height,s,cfg,color,externalPercent(cfg,s)?1:0);
    }
    public void draw(Canvas c,float width,float height,SignalModel s,Config cfg,int color,float position){
        int checkpoint=c.save();
        try {
            position=clampPosition(position);
            float w=logicalWidth(cfg,s,position),scale=Math.min(width/w,height/132f);
            c.translate((width-w*scale)/2,(height-132*scale)/2);c.scale(scale,scale);
            ink=color|0xff000000;dim=(ink&0x00ffffff)|0x48000000;weight=cfg.weight/100f;
            accent=!cfg.colors?ink:s.charging?Color.rgb(49,190,125):s.battery>=0&&s.battery<=20?Color.rgb(230,96,67):ink;
            p.setTypeface(boldFace);
            if(cfg.style==0)compact(c,s,position);else if(cfg.style==1)external(c,s,cfg,position);else rectangular(c,s,cfg,position);
            float x=baseWidth(cfg,s);
            if(cfg.networkType&&cfg.style!=1&&s.presentCount()>0){label(c,s.type(),x+networkWidth()/2,64);x+=networkWidth();}
            batteryNumber(c,s,cfg,x,position);
        }finally{c.restoreToCount(checkpoint);}
    }
    private static int opacity(int color,float amount){return (color&0x00ffffff)|(Math.round(Color.alpha(color)*clampPosition(amount))<<24);}
    private void batteryNumber(Canvas c,SignalModel s,Config cfg,float rightStart,float position){
        String number=s.battery<0?"—":s.battery+"";
        // One glyph fades out with a short slide, then enters at the other anchor.
        // The invisible midpoint avoids crossing the Wi-Fi or charging lightning.
        if(position<.5f){
            float away=position*2;
            float x=(cfg.style==1?ringCenter(s):60)+(cfg.style==0?0:12*away);
            txt(c,number,x,cfg.style==1?111:cfg.style==0?95+2*away:101,cfg.batteryText,opacity(accent,1-away));
        }else if(position>.5f){
            float arrived=(position-.5f)*2;
            p.setTextSize(cfg.rightBatteryText);Paint.FontMetrics metrics=p.getFontMetrics();
            float centerY=cfg.style==1?57:cfg.style==0?66:60;
            p.getTextBounds(number,0,number.length(),numberBounds);
            p.setStyle(Paint.Style.FILL);p.setTextAlign(Paint.Align.LEFT);p.setColor(opacity(accent,arrived));
            boolean extra=cfg.networkType&&cfg.style!=1&&s.presentCount()>0;
            float left=rightStart+rightOffset(cfg,extra)+12*(1-arrived);
            c.drawText(number,left-numberBounds.left,centerY-(metrics.ascent+metrics.descent)/2,p);
        }
    }
    private void arc(Canvas c,float cx,float cy,float r,float start,float sweep,int color,float stroke){
        if(sweep<=0)return;
        p.setColor(color);p.setStyle(Paint.Style.STROKE);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeWidth(stroke*weight);
        rect.set(cx-r,cy-r,cx+r,cy+r);c.drawArc(rect,start,sweep,false,p);
    }
    private void circle(Canvas c,float x,float y,float r,int color){p.setStyle(Paint.Style.FILL);p.setColor(color);c.drawCircle(x,y,r*weight,p);}
    private void txt(Canvas c,String s,float x,float baseline,float size,int color){p.setStyle(Paint.Style.FILL);p.setColor(color);p.setTextAlign(Paint.Align.CENTER);p.setTextSize(size);c.drawText(s,x,baseline,p);}
    private void label(Canvas c,String s,float x,float centerY){
        p.setTypeface(labelFace);p.setTextSize(labelSize);
        Paint.FontMetrics metrics=p.getFontMetrics();txt(c,s,x,centerY-(metrics.ascent+metrics.descent)/2,labelSize,ink);
        p.setTypeface(boldFace);
    }
    private void batteryArc(Canvas c,float x,float y,SignalModel s,boolean split){
        float fraction=s.battery<0?0:Config.clamp(s.battery,0,100)/100f;
        if(split){
            arc(c,x,y,48,310,100,dim,9);arc(c,x,y,48,130,100,dim,9);
            arc(c,x,y,48,310,Math.min(100,200*fraction),accent,9);
            arc(c,x,y,48,130,Math.max(0,200*fraction-100),accent,9);
        }else{arc(c,x,y,48,140,260,dim,9);arc(c,x,y,48,140,260*fraction,accent,9);}
    }
    private void wifi(Canvas c,float x,float y,SignalModel s){
        if(s.wifi){
            // Three separated thin arcs plus a dot distinguish all four active levels.
            arc(c,x,y+24,30,220,100,s.wifiLevel>=4?ink:dim,4.2f);
            arc(c,x,y+24,20,220,100,s.wifiLevel>=3?ink:dim,4.2f);
            arc(c,x,y+24,10,220,100,s.wifiLevel>=2?ink:dim,4.2f);
            circle(c,x,y+26,3.4f,s.wifiLevel>=1?ink:dim);
        }
    }
    private void row(Canvas c,SignalModel s,int slot,float y){
        if(s.levels[slot]==-2)return;
        for(int i=0;i<4;i++){
            circle(c,33+i*18,y,4.8f,s.levels[slot]>i&&!s.airplane?ink:dim);
        }
        if(s.levels[slot]<0)txt(c,"?",106,y+4,12,ink);
    }
    private void compact(Canvas c,SignalModel s,float position){
        float fraction=s.battery<0?0:Config.clamp(s.battery,0,100)/100f;
        if(s.presentCount()==2){
            // Matching top/bottom mouths and mirrored side arcs leave equal dot clearance.
            arc(c,60,66,46,315,90,dim,9);arc(c,60,66,46,135,90,dim,9);
            arc(c,60,66,46,315,90*fraction,accent,9);
            arc(c,60,66,46,225-90*fraction,90*fraction,accent,9);
        }else{
            // Close unused mouths; a lone SIM stays in its physical slot's row.
            float start=s.levels[0]!=-2?315:s.levels[1]!=-2?135:270;
            float sweep=s.presentCount()==0?360:270;
            arc(c,60,66,46,start,sweep,dim,9);arc(c,60,66,46,start,sweep*fraction,accent,9);
        }
        row(c,s,0,15);row(c,s,1,117);
        c.save();c.translate(60,37);c.scale(.85f,.85f);wifi(c,0,0,s);c.restore();
        if(s.charging)chargeMark(c,s,60,74,1.2f,Math.max(0,position*2-1));
    }
    private void external(Canvas c,SignalModel s,Config cfg,float position){
        int count=s.presentCount();
        if(count==2){
            float separation=Math.max(42,labelSize*1.2f);
            externalSignalRow(c,s,0,65-separation/2);
            externalSignalRow(c,s,1,65+separation/2);
        }else if(count==1)externalSignalRow(c,s,s.levels[0]!=-2?0:1,66);
        float center=ringCenter(s);
        batteryArc(c,center,57,s,false);wifi(c,center,48,s);
        if(s.charging)chargeMark(c,s,center,82,1.65f,Math.max(0,position*2-1));
    }
    private void externalSignalRow(Canvas c,SignalModel s,int slot,float centerY){
        if(s.levels[slot]==-2)return;
        label(c,"卡"+(slot+1),cardSpan/2,centerY);
        // Leave at least three logical pixels between neighbouring dots at every weight.
        for(int i=0;i<4;i++)circle(c,dotStart+i*20,centerY,Math.min(8.2f,8.5f/weight),s.levels[slot]>i&&!s.airplane?ink:dim);
        label(c,s.typeForSlot(slot),networkX,centerY);
        if(s.levels[slot]<0)txt(c,"?",dotStart+77,centerY+7,19,ink);
    }
    private void rectangular(Canvas c,SignalModel s,Config cfg,float position){
        for(int slot=0;slot<2;slot++)if(s.levels[slot]!=-2)for(int i=0;i<4;i++)circle(c,slot==0?15:105,25+i*23,4.4f,s.levels[slot]>i&&!s.airplane?ink:dim);
        wifi(c,60,34,s);
        if(s.charging)chargeMark(c,s,60,72,1.5f,Math.max(0,position*2-1));
        p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(7*weight);p.setStrokeCap(Paint.Cap.ROUND);p.setColor(dim);c.drawLine(14,122,106,122,p);
        if(s.battery>0){p.setColor(accent);c.drawLine(14,122,14+92*Config.clamp(s.battery,0,100)/100f,122,p);}
    }
    private void chargeMark(Canvas c,SignalModel s,float centerX,float top,float scale){
        int count=s.chargeBoltCount();if(count==0)return;
        float left=centerX-(count==2?38:18)*scale/2;
        bolt(c,left,top,scale);
        if(count==2)bolt(c,left+20*scale,top,scale);
    }
    private void chargeMark(Canvas c,SignalModel s,float centerX,float top,float scale,float alpha){
        int previous=accent;accent=opacity(accent,alpha);
        try{chargeMark(c,s,centerX,top,scale);}finally{accent=previous;}
    }
    private void bolt(Canvas c,float x,float y,float scale){
        c.save();c.translate(x,y);c.scale(scale,scale);path.reset();path.moveTo(10,0);path.lineTo(0,15);path.lineTo(7,15);path.lineTo(4,27);path.lineTo(18,9);path.lineTo(11,9);path.close();p.setStyle(Paint.Style.FILL);p.setColor(accent);c.drawPath(path,p);c.restore();
    }
}
