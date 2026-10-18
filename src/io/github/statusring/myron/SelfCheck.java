package io.github.statusring.myron;

import android.content.Context;
import android.graphics.*;
import android.util.Log;
import java.io.*;

/** On-device rendering checks; uses sample data, never modifies a real radio or status bar. */
public final class SelfCheck {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static String run(Context c){
        int checks=0;
        try{
            require(SignalModel.networkName(13,3).equals("5G"),"NSA 5G overlay");checks++;
            require(SignalModel.networkName(13,0).equals("4G"),"LTE");checks++;
            SignalModel slots=SignalModel.demo();slots.dataSlot=1;require(slots.type().equals("4G")&&slots.levels[0]==3&&slots.levels[1]==2,"Data SIM switch preserves physical ordering");checks++;
            Config options=new Config();require(options.accepts(0)&&options.accepts(1)&&!options.accepts(3),"AOD remains stock");checks++;
            options.enabled=false;require(!options.accepts(0),"Disabled restores stock");checks++;
            RingPainter painter=new RingPainter();
            for(int weight:new int[]{75,100,140}){
                Config cfg=new Config();cfg.weight=weight;SignalModel full=SignalModel.demo();full.levels[0]=4;full.levels[1]=4;
                Bitmap image=Bitmap.createBitmap(328,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(image),328,132,full,cfg,Color.WHITE);
                for(int row:new int[]{44,86})for(int gap=0;gap<3;gap++)require(Color.alpha(image.getPixel(88+20*gap,row))==0,"Signal dots merge at weight="+weight);
                checks++;image.recycle();
            }
            // Equal SIM readings must have pixel-identical dots, translated vertically.
            for(int level=0;level<=4;level++){
                Config cfg=new Config();cfg.style=1;
                SignalModel same=SignalModel.demo();same.levels[0]=level;same.levels[1]=level;
                Bitmap rows=Bitmap.createBitmap(328,132,Bitmap.Config.ARGB_8888);
                painter.draw(new Canvas(rows),328,132,same,cfg,Color.WHITE);
                for(int y=33;y<=55;y++)for(int x=68;x<152;x++)require(rows.getPixel(x,y)==rows.getPixel(x,y+42),"Different dual-SIM dot geometry at level "+level);
                checks++;rows.recycle();
            }
            // A non-data SIM's RAT must remain visible with Wi-Fi on and while charging.
            for(boolean charging:new boolean[]{false,true}){
                Config cfg=new Config();cfg.style=1;
                SignalModel perSim=SignalModel.demo();perSim.charging=charging;perSim.dataSlot=0;
                Bitmap before=Bitmap.createBitmap(328,132,Bitmap.Config.ARGB_8888);
                painter.draw(new Canvas(before),328,132,perSim,cfg,Color.WHITE);
                perSim.types[1]="5G";
                Bitmap after=Bitmap.createBitmap(328,132,Bitmap.Config.ARGB_8888);
                painter.draw(new Canvas(after),328,132,perSim,cfg,Color.WHITE);
                require(!before.sameAs(after),"SIM 2 RAT hidden with Wi-Fi or charging");checks++;
                before.recycle();after.recycle();
            }
            // Strong and very strong Wi-Fi must no longer collapse to the same shape.
            {
                Config cfg=new Config();cfg.style=1;SignalModel wifi=SignalModel.demo();wifi.wifiLevel=3;
                Bitmap before=Bitmap.createBitmap(328,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(before),328,132,wifi,cfg,Color.WHITE);
                wifi.wifiLevel=4;
                Bitmap after=Bitmap.createBitmap(328,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(after),328,132,wifi,cfg,Color.WHITE);
                require(!before.sameAs(after),"Wi-Fi levels 3 and 4 should have distinct arcs");checks++;
                before.recycle();after.recycle();
            }
            // The real charger is never spoofed: validate both drawing paths with samples.
            for(int style=0;style<3;style++){
                Config cfg=new Config();cfg.style=style;SignalModel charge=SignalModel.demo();charge.charging=true;
                int w=Math.round(painter.logicalWidth(cfg,charge));
                Bitmap normal=Bitmap.createBitmap(w,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(normal),w,132,charge,cfg,Color.WHITE);
                charge.fastCharging=true;
                Bitmap fast=Bitmap.createBitmap(w,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(fast),w,132,charge,cfg,Color.WHITE);
                require(!normal.sameAs(fast),"Fast and normal charging need distinct lightning icons, style="+style);checks++;
                normal.recycle();fast.recycle();
                charge.charging=false;
                Bitmap stale=Bitmap.createBitmap(w,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(stale),w,132,charge,cfg,Color.WHITE);
                charge.fastCharging=false;
                Bitmap unplugged=Bitmap.createBitmap(w,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(unplugged),w,132,charge,cfg,Color.WHITE);
                require(stale.sameAs(unplugged),"Stale quick-charge flag must not render lightning, style="+style);checks++;
                stale.recycle();unplugged.recycle();
            }
            {
                Config cfg=new Config();cfg.style=1;cfg.percent=true;cfg.batteryText=40;
                Paint measure=new Paint(Paint.ANTI_ALIAS_FLAG);measure.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));measure.setTextSize(cfg.batteryText);
                require(measure.measureText("100")+8<painter.logicalWidth(cfg)-328,"Three-digit enlarged battery percentage would clip");checks++;
            }
            // Charging reserves one number column; unplugging restores the exact original image.
            for(int style=0;style<3;style++){
                Config cfg=new Config();cfg.style=style;cfg.batteryText=40;
                SignalModel model=SignalModel.demo();model.battery=100;
                Bitmap unplugged=render(painter,cfg,model);int base=unplugged.getWidth();
                model.charging=true;
                Bitmap charged=render(painter,cfg,model);
                require(charged.getWidth()>base&&painter.externalPercent(cfg,model),"Charging did not reserve number column, style="+style);checks++;
                model.battery=7;Bitmap seven=render(painter,cfg,model);
                Bitmap hundredNumber=Bitmap.createBitmap(charged,base,0,charged.getWidth()-base,132);
                Bitmap sevenNumber=Bitmap.createBitmap(seven,base,0,seven.getWidth()-base,132);
                require(!hundredNumber.sameAs(sevenNumber),"Charging right-hand number did not update, style="+style);checks++;
                hundredNumber.recycle();sevenNumber.recycle();seven.recycle();model.battery=100;
                cfg.percent=true;Bitmap forced=render(painter,cfg,model);
                require(charged.sameAs(forced),"Charging percentage duplicated with persistent right-hand option, style="+style);checks++;
                forced.recycle();cfg.percent=false;model.charging=false;model.fastCharging=true;
                Bitmap returned=render(painter,cfg,model);
                require(!painter.externalPercent(cfg,model)&&returned.sameAs(unplugged),"Unplug did not restore exact original layout, style="+style);checks++;
                if(style!=0){
                    model.battery=7;Bitmap lowerSeven=render(painter,cfg,model);
                    int cx=style==1?(int)painter.ringCenter(model):60,top=style==1?95:87;
                    Bitmap lowerHundred=Bitmap.createBitmap(returned,cx-35,top,70,25);
                    Bitmap lowerNumber=Bitmap.createBitmap(lowerSeven,cx-35,top,70,25);
                    require(!lowerHundred.sameAs(lowerNumber),"Unplugged number missing below inner Wi-Fi, style="+style);checks++;
                    lowerHundred.recycle();lowerNumber.recycle();lowerSeven.recycle();
                }
                unplugged.recycle();charged.recycle();returned.recycle();
            }
            // Matching an absolute 21px stock font must still fit at different icon heights.
            {
                Config cfg=new Config();SignalModel model=SignalModel.demo();model.charging=true;model.battery=100;
                Bitmap enlarged=render(painter,cfg,model);cfg.rightBatteryText=35;Bitmap previous=render(painter,cfg,model);
                int start=(int)painter.baseWidth(cfg,model);
                require(numberHeight(enlarged,start)>numberHeight(previous,start),"Right-hand battery number did not grow");checks++;
                enlarged.recycle();previous.recycle();cfg.rightBatteryText=56;
                Paint measure=new Paint(Paint.ANTI_ALIAS_FLAG);measure.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));measure.setTextSize(cfg.rightBatteryText);
                require(measure.measureText("100")+12<painter.logicalWidth(cfg,model)-start,"Maximum right-hand font clips a three-digit number");checks++;
            }
            for(int style:new int[]{1,2})for(int height:new int[]{48,84}){
                RingPainter moving=new RingPainter();moving.setLabelStyle(21*132f/height,Typeface.create("sans-serif",Typeface.BOLD));
                Config cfg=new Config();cfg.style=style;cfg.batteryText=40;cfg.rightBatteryText=56;
                SignalModel model=SignalModel.demo();model.charging=true;model.fastCharging=true;model.battery=100;
                for(float position:new float[]{0,.2f,.4f,.5f,.6f,.8f,1}){
                    int width=(int)Math.ceil(height*moving.logicalWidth(cfg,model,position)/132f);
                    Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);moving.draw(new Canvas(image),width,height,model,cfg,Color.WHITE,position);
                    for(int x=0;x<width;x++)require(Color.alpha(image.getPixel(x,0))==0&&Color.alpha(image.getPixel(x,height-1))==0,"Motion clips vertically, style="+style+" position="+position);
                    for(int y=0;y<height;y++)require(Color.alpha(image.getPixel(0,y))==0&&Color.alpha(image.getPixel(width-1,y))==0,"Motion clips horizontally, style="+style+" position="+position);
                    image.recycle();
                }
                checks++;
            }
            for(int height:new int[]{48,66,84})for(boolean fast:new boolean[]{false,true}){
                RingPainter matched=new RingPainter();matched.setLabelStyle(21*132f/height,Typeface.create("sans-serif",Typeface.BOLD));
                Config cfg=new Config();SignalModel sample=SignalModel.demo();sample.charging=true;sample.fastCharging=fast;
                int width=(int)Math.ceil(height*matched.logicalWidth(cfg,sample)/132f);
                Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);matched.draw(new Canvas(image),width,height,sample,cfg,Color.WHITE);
                for(int x=0;x<width;x++)require(Color.alpha(image.getPixel(x,0))==0&&Color.alpha(image.getPixel(x,height-1))==0,"Matched labels/lightning clipped vertically at height="+height);
                for(int y=0;y<height;y++)require(Color.alpha(image.getPixel(0,y))==0&&Color.alpha(image.getPixel(width-1,y))==0,"Matched labels/lightning clipped horizontally at height="+height);
                checks++;image.recycle();
            }
            // A disconnected Wi-Fi must leave the centre empty, including airplane mode.
            for(int style=0;style<3;style++)for(boolean airplane:new boolean[]{false,true}){
                Config cfg=new Config();cfg.style=style;SignalModel mobile=SignalModel.demo();mobile.wifi=false;mobile.airplane=airplane;mobile.charging=true;
                int w=(int)painter.logicalWidth(cfg,mobile);Bitmap image=Bitmap.createBitmap(w,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(image),w,132,mobile,cfg,Color.WHITE);
                int cx=style==1?(int)painter.ringCenter(mobile):60,cy=style==2?40:55;
                for(int y=cy-10;y<=cy+10;y++)for(int x=cx-18;x<=cx+18;x++)require(Color.alpha(image.getPixel(x,y))==0,"Mobile-only ring centre has content, style="+style);
                checks++;image.recycle();
            }
            // Both physical single-SIM cases must occupy the middle row, never the old upper/lower rows.
            for(int slot=0;slot<2;slot++){
                Config cfg=new Config();SignalModel single=SignalModel.demo();single.levels[1-slot]=-2;single.dataSlot=slot;
                int w=(int)painter.logicalWidth(cfg,single);Bitmap image=Bitmap.createBitmap(w,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(image),w,132,single,cfg,Color.WHITE);
                int top=132,bottom=-1;
                for(int y=0;y<132;y++)for(int x=0;x<(int)painter.ringCenter(single)-62;x++)if(Color.alpha(image.getPixel(x,y))>0){top=Math.min(top,y);bottom=Math.max(bottom,y);}
                require(top>=45&&bottom<=87&&bottom>top,"Physical card "+(slot+1)+" is not vertically centred");checks++;image.recycle();
            }
            // No SIM draws exactly the ring section, with no placeholder and no reserved card area.
            for(boolean connected:new boolean[]{false,true}){
                Config cfg=new Config();SignalModel dual=SignalModel.demo();dual.wifi=connected;
                int wide=(int)painter.logicalWidth(cfg,dual);Bitmap before=Bitmap.createBitmap(wide,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(before),wide,132,dual,cfg,Color.WHITE);
                SignalModel empty=SignalModel.demo();empty.wifi=connected;empty.levels[0]=-2;empty.levels[1]=-2;
                int narrow=(int)painter.logicalWidth(cfg,empty);Bitmap after=Bitmap.createBitmap(narrow,132,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(after),narrow,132,empty,cfg,Color.WHITE);
                Bitmap ringOnly=Bitmap.createBitmap(before,wide-narrow,0,narrow,132);
                require(narrow<wide&&ringOnly.sameAs(after),"No-SIM rendering reserves a card area or draws a placeholder");checks++;
                before.recycle();after.recycle();ringOnly.recycle();
            }
            Bitmap sheet=Bitmap.createBitmap(900,1080,Bitmap.Config.ARGB_8888);Canvas grid=new Canvas(sheet);grid.drawColor(0xff0b111b);
            Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(Color.WHITE);text.setTextSize(17);
            for(int style=0;style<3;style++)for(int state=0;state<8;state++){
                Config cfg=new Config();cfg.style=style;
                SignalModel model=SignalModel.demo();
                if(state==1){model.charging=true;model.battery=35;}
                if(state==2){model.battery=15;model.wifi=false;}
                if(state==3){model.levels[1]=-2;}
                if(state==4){model.levels[0]=-2;model.dataSlot=1;}
                if(state==5){model.airplane=true;model.levels[0]=0;model.levels[1]=0;model.wifi=false;}
                if(state==6){model.levels[0]=-1;model.levels[1]=-1;model.battery=-1;model.wifi=false;}
                if(state==7){model.dataSlot=1;model.wifi=false;cfg.percent=true;cfg.networkType=true;}
                int w=Math.round(88*painter.logicalWidth(cfg,model)/132f);
                Bitmap b=Bitmap.createBitmap(w,88,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(b),w,88,model,cfg,Color.WHITE);
                int drawn=0;for(int y=0;y<88;y++)for(int x=0;x<w;x++)if(Color.alpha(b.getPixel(x,y))>0)drawn++;
                require(drawn>50,"Empty rendering: "+style+"/"+state);checks++;
                float x=style*300+18,y=state*135+13;
                grid.drawBitmap(b,x,y,null);grid.drawText("ABC".charAt(style)+" / state "+state,x,y+114,text);
                // Every physical SIM must independently affect the output, including with Wi-Fi on.
                if(state==0){
                    model.levels[1]=4;Bitmap second=Bitmap.createBitmap(w,88,Bitmap.Config.ARGB_8888);painter.draw(new Canvas(second),w,88,model,cfg,Color.WHITE);
                    require(!b.sameAs(second),"SIM 2 lost while Wi-Fi is connected, style="+style);checks++;second.recycle();
                }
                b.recycle();
            }
            File output=new File(c.getFilesDir(),"render-checks.png");try(FileOutputStream f=new FileOutputStream(output)){sheet.compress(Bitmap.CompressFormat.PNG,100,f);}sheet.recycle();
            saveBPreview(c,painter);
            saveCardPreview(c,painter);
            saveChargePreview(c,painter);
            String result="PASS "+checks+" checks\n右侧电量独立放大；最大字号三位数与动画中间帧不裁切\n自动换位、拔线恢复、流量留空、单卡居中、无卡隐藏、单双闪电检查完成\n这是应用内示例绘制检查，SystemUI 实际注入另外验证。";
            try(FileOutputStream f=new FileOutputStream(new File(c.getFilesDir(),"selfcheck.txt"))){f.write(result.getBytes("UTF-8"));}
            Log.i("StatusRingTest",result);return result;
        }catch(Throwable error){Log.e("StatusRingTest","FAILED",error);return "FAILED: "+error;}
    }
    private static Bitmap render(RingPainter painter,Config cfg,SignalModel model){
        int width=(int)Math.ceil(painter.logicalWidth(cfg,model));
        Bitmap image=Bitmap.createBitmap(width,132,Bitmap.Config.ARGB_8888);
        painter.draw(new Canvas(image),width,132,model,cfg,Color.WHITE);return image;
    }
    private static int numberHeight(Bitmap image,int from){
        int top=image.getHeight(),bottom=-1;
        for(int y=0;y<image.getHeight();y++)for(int x=from;x<image.getWidth();x++)if(Color.alpha(image.getPixel(x,y))>0){top=Math.min(top,y);bottom=Math.max(bottom,y);}
        return bottom<top?0:bottom-top+1;
    }
    private static void saveChargePreview(Context context,RingPainter painter)throws IOException{
        Bitmap image=Bitmap.createBitmap(1280,850,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(0xff0b111b);
        Paint text=new Paint(Paint.ANTI_ALIAS_FLAG);text.setColor(0xffedf3fb);text.setTextSize(40);canvas.drawText("电量数字随充电状态自动换位",60,78,text);
        Config cfg=Config.read(context.getSharedPreferences("config",Context.MODE_PRIVATE));cfg.style=1;cfg.percent=false;
        String[] labels={"未充电／拔线后：圈内下方","普通充电：右侧数字＋单闪电","快速充电：右侧数字＋双闪电"};
        for(int i=0;i<3;i++){
            SignalModel model=SignalModel.demo();model.battery=86;model.charging=i>0;model.fastCharging=i==2;
            text.setColor(0xffa9bed7);text.setTextSize(27);canvas.drawText(labels[i],60,180+i*215,text);
            float height=132,width=painter.logicalWidth(cfg,model);
            canvas.save();canvas.translate(1210-width,205+i*215);painter.draw(canvas,width,height,model,cfg,Color.WHITE);canvas.restore();
        }
        text.setTextSize(21);text.setColor(0xff839bb7);canvas.drawText("手机原生绘制示例；非真实充电状态截图。",60,824,text);
        try(FileOutputStream stream=new FileOutputStream(new File(context.getFilesDir(),"battery-position-preview.png"))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}image.recycle();
    }
    private static void saveCardPreview(Context context,RingPainter painter)throws IOException{
        Bitmap image=Bitmap.createBitmap(1280,960,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(0xff0b111b);
        Paint caption=new Paint(Paint.ANTI_ALIAS_FLAG);caption.setColor(0xffedf3fb);caption.setTextSize(40);canvas.drawText("流量留空 · 单卡居中 · 无卡隐藏",60,80,caption);
        String[] labels={"双卡 · 使用流量","仅卡1 · 使用流量","仅卡2 · Wi-Fi","无卡 · Wi-Fi"};
        for(int i=0;i<4;i++){
            SignalModel model=SignalModel.demo();if(i<2)model.wifi=false;if(i==1){model.levels[1]=-2;model.dataSlot=0;}if(i==2){model.levels[0]=-2;model.dataSlot=1;}if(i==3){model.levels[0]=-2;model.levels[1]=-2;}
            Config cfg=new Config();float height=145,width=painter.logicalWidth(cfg,model)*height/132;
            caption.setColor(0xffa9bed7);caption.setTextSize(29);canvas.drawText(labels[i],60,210+i*190,caption);
            canvas.save();canvas.translate(1190-width,130+i*190);painter.draw(canvas,width,height,model,cfg,Color.WHITE);canvas.restore();
        }
        caption.setTextSize(21);caption.setColor(0xff839bb7);canvas.drawText("手机原生绘制代码生成；示例状态，未切换实际 SIM 或网络。",60,935,caption);
        try(FileOutputStream stream=new FileOutputStream(new File(context.getFilesDir(),"card-layout-preview.png"))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}image.recycle();
    }
    private static void saveBPreview(Context context,RingPainter painter)throws IOException{
        Bitmap image=Bitmap.createBitmap(1400,1000,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(image);c.drawColor(0xff0b111b);
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);p.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));p.setColor(0xffeef4fc);p.setTextSize(46);c.drawText("B · SIM 标识与 Wi-Fi 精修版",70,94,p);
        p.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));p.setColor(0xff9eb6d0);p.setTextSize(24);c.drawText("卡1／卡2 · 字号跟随网速 · 放大充电闪电",70,147,p);
        Config cfg=new Config();cfg.style=1;
        SignalModel model=SignalModel.demo();
        c.save();c.translate(140,220);painter.draw(c,1120,420,model,cfg,Color.WHITE);c.restore();
        p.setColor(0xff9eb6d0);p.setTextSize(25);c.drawText("缩小预览",95,789,p);
        p.setColor(0xffeaf0f8);c.drawRoundRect(320,738,1280,858,18,18,p);
        p.setColor(0xff182536);p.setTextSize(35);c.drawText("09:41",351,813,p);
        c.save();c.translate(1005,756);painter.draw(c,220,82.5f,model,cfg,0xff182536);c.restore();
        p.setColor(0xff839bb7);p.setTextSize(22);c.drawText("使用 APK 相同的 Android Canvas 绘制代码生成；示例读数，非状态栏启用截图。",70,947,p);
        try(FileOutputStream stream=new FileOutputStream(new File(context.getFilesDir(),"B-refined-preview.png"))){image.compress(Bitmap.CompressFormat.PNG,100,stream);}image.recycle();
    }
}
