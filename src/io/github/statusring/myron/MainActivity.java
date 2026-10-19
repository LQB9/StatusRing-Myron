package io.github.statusring.myron;

import android.app.Activity;
import android.animation.ValueAnimator;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import io.github.libxposed.service.*;

public final class MainActivity extends Activity implements XposedServiceHelper.OnServiceListener {
    public static final String PROJECT_URL="https://github.com/LQB9/StatusRing-Myron";
    private Config config;
    private SharedPreferences local;
    private XposedService service;
    private TextView status;
    private Button restartButton;
    private Switch extraType;
    private Preview preview;
    private LinearLayout content;
    private boolean creating;
    private int dp(float x){return Math.round(getResources().getDisplayMetrics().density*x);}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setPadding(0,dp(8),0,dp(8));return t;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        if(getIntent().getBooleanExtra("selfcheck",false)){
            TextView test=text(SelfCheck.run(this),16,Color.WHITE);test.setPadding(dp(20),dp(50),dp(20),dp(20));setContentView(test);return;
        }
        local=getSharedPreferences("config",MODE_PRIVATE);config=Config.read(local);creating=true;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xff0b111b);
        content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(22),dp(16),dp(22),dp(28));scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(i.left,i.top,i.right,i.bottom);return insets;});
        TextView title=text("环形三合一 · 双卡",27,0xffedf3fb);title.setTypeface(null,Typeface.BOLD);content.addView(title);
        content.addView(text(Build.MANUFACTURER+" "+Build.MODEL+"  ·  Android "+Build.VERSION.RELEASE,13,0xff97aec8));
        status=text("框架状态：等待连接",13,0xff85d5e9);content.addView(status);
        restartButton=new Button(this);restartButton.setText("重启系统界面");restartButton.setTextSize(14);restartButton.setTextColor(0xffc0f0f8);restartButton.setAllCaps(false);restartButton.setEnabled(false);
        content.addView(restartButton,new LinearLayout.LayoutParams(-1,-2));restartButton.setOnClickListener(v->restartSystemUi());
        content.addView(text("刷新状态栏与通知栏，通常几秒后恢复。",11,0xff91a7c1));
        Button compatibility=new Button(this);compatibility.setText("查看兼容检测");compatibility.setAllCaps(false);content.addView(compatibility);
        compatibility.setOnClickListener(v->showCompatibility());
        preview=new Preview();content.addView(preview,new LinearLayout.LayoutParams(-1,dp(155)));
        content.addView(text("点按预览查看切换动画。充电时数字移到右侧，拔线后回到圈内下方。",12,0xff91a7c1));
        toggle("启用三合一",config.enabled,b->config.enabled=b);
        content.addView(text("图标布局",15,0xffc6d6e8));
        Spinner layouts=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"A · 对称圆环","B · 圆环＋环外双卡","C · 紧凑矩形"});adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);layouts.setAdapter(adapter);layouts.setSelection(config.style);content.addView(layouts);
        layouts.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> a){}public void onItemSelected(AdapterView<?> a,View v,int pos,long id){config.style=pos;if(extraType!=null)extraType.setEnabled(pos!=1);save();}});
        slider("图标高度",16,28,config.size," dp",value->config.size=value);
        slider("线条粗细",75,140,config.weight,"%",value->config.weight=value);
        slider("圈内电量字号",26,40,config.batteryText,"",value->config.batteryText=value);
        slider("右侧电量字号",36,56,config.rightBatteryText,"",value->config.rightBatteryText=value);
        toggle("充电、低电量提示色",config.colors,b->config.colors=b);
        toggle("始终在右侧显示电量数字",config.percent,b->config.percent=b);
        extraType=toggle("额外显示上网卡制式（A/C）",config.networkType,b->config.networkType=b);extraType.setEnabled(config.style!=1);
        toggle("锁屏状态栏也使用",config.lockscreen,b->config.lockscreen=b);
        toggle("通知栏／控制中心也使用（实验）",config.panels,b->config.panels=b);
        content.addView(text("双卡规则",17,0xffedf3fb));
        content.addView(text("A：顶部卡1，底部卡2，圆点大小和间距一致；两侧电量弧镜像对称。未插卡的一排隐藏，并闭合对应开口。\nB：双卡上下两排；只有一张卡时整行居中，保留真实卡号；无卡时隐藏卡片区域并收起空位。卡号和 4G／5G 字号跟随系统网速文字，接口缺失时使用默认字体。\nC：左列卡1，右列卡2。\n圆环中心仅在 Wi-Fi 连接时显示 Wi-Fi；使用流量时留空。充电时数字自动在右侧显示，内部下方保留单／双闪电。关闭‘始终在右侧’后，拔线时数字回到内部下方，右侧空位同时收起。\n点亮越多表示信号越强。B 的网络制式跟随每张卡的系统读数，未知或无服务显示 —。切换上网卡不会交换卡号。",13,0xffa9bed7));
        content.addView(text("启用方法",17,0xffedf3fb));
        content.addView(text("在 LSPosed 中启用本模块，只勾选「系统界面」。首次启用后重启作用域；以后调整上述选项即时生效。关闭总开关即可恢复系统图标。",13,0xffa9bed7));
        content.addView(text("1.0.14 · 按接口检测 MIUI / HyperOS；其他机型为实验兼容\n实测组合：myron / HyperOS 3 / Android 16。ColorOS、One UI 等尚无适配器。\n布局参考：HyperDuo；双卡读取参考：SignalNumbers 文档。实现源码独立编写。",11,0xff7b94ae));
        TextView project=text("GitHub 项目\n"+PROJECT_URL,12,0xff97aec8);
        project.setLinkTextColor(0xff85d5e9);android.text.util.Linkify.addLinks(project,android.text.util.Linkify.WEB_URLS);
        project.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());content.addView(project);
        setContentView(scroll);creating=false;FrameworkConnection.attach(this);
    }
    @Override protected void onDestroy(){FrameworkConnection.detach(this);super.onDestroy();}
    private void showCompatibility(){
        SharedPreferences report=getSharedPreferences("compatibility",MODE_PRIVATE);
        String details=report.getString("report","");
        if(details.isEmpty())details="尚未收到系统界面的运行报告。\n请在 LSPosed 启用模块并勾选系统界面，再重启系统界面。";
        else{
            int boot=android.provider.Settings.Global.getInt(getContentResolver(),"boot_count",-1);
            if(!CompatibilityProvider.VERSION.equals(report.getString("version",""))||report.getInt("boot",-2)!=boot)
                details="这是上次运行的记录，请重启系统界面获取当前检测结果。\n\n"+details;
            details+="\n记录时间："+android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss",report.getLong("time",0));
        }
        new android.app.AlertDialog.Builder(this).setTitle("兼容检测 · SystemUI 实际报告").setMessage(details).setPositiveButton("关闭",null).show();
    }
    private interface BoolSetter{void put(boolean value);}
    private void restartSystemUi(){
        if(!canRestart()){Toast.makeText(this,"请先在 LSPosed 启用模块并勾选系统界面",Toast.LENGTH_LONG).show();return;}
        save();restartButton.setEnabled(false);
        try{
            SharedPreferences remote=service.getRemotePreferences("config");
            if(!remote.edit().putLong("restartRequest",System.currentTimeMillis()).commit())throw new IllegalStateException("request not saved");
            Toast.makeText(this,"已请求重启系统界面，稍后查看状态栏",Toast.LENGTH_LONG).show();
        }catch(Throwable t){Toast.makeText(this,"未能发送重启请求，请检查 LSPosed 连接",Toast.LENGTH_LONG).show();}
        restartButton.postDelayed(()->{if(!isDestroyed())restartButton.setEnabled(canRestart());},5000);
    }
    private boolean canRestart(){try{return service!=null&&service.getScope().contains("com.android.systemui");}catch(Throwable t){return false;}}
    private interface IntSetter{void put(int value);}
    private Switch toggle(String label,boolean value,BoolSetter setter){Switch control=new Switch(this);control.setText(label);control.setTextColor(0xffd6e3f2);control.setTextSize(14);control.setPadding(0,dp(12),0,dp(12));control.setChecked(value);content.addView(control);control.setOnCheckedChangeListener((b,on)->{setter.put(on);save();});return control;}
    private void slider(String label,int min,int max,int value,String unit,IntSetter setter){TextView heading=text(label+"  "+value+unit,14,0xffc6d6e8);content.addView(heading);SeekBar bar=new SeekBar(this);bar.setMax(max-min);bar.setProgress(value-min);content.addView(bar);bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){save();}public void onProgressChanged(SeekBar s,int progress,boolean user){if(user){setter.put(progress+min);heading.setText(label+"  "+(progress+min)+unit);preview.invalidate();}}});}
    private void save(){if(creating)return;config.write(local);if(service!=null)try{config.write(service.getRemotePreferences("config"));}catch(Throwable t){status.setText("框架连接暂不可用，设置已保存在本机");}if(preview!=null)preview.invalidate();}
    @Override public void onServiceBind(XposedService s){runOnUiThread(()->{if(isDestroyed())return;service=s;save();try{boolean scoped=s.getScope().contains("com.android.systemui");restartButton.setEnabled(scoped);status.setText("已连接 "+s.getFrameworkName()+" "+s.getFrameworkVersion()+" · API "+s.getApiVersion()+"\n"+(scoped?"系统界面作用域已勾选":"请在 LSPosed 中勾选系统界面"));}catch(Throwable t){restartButton.setEnabled(false);status.setText("框架连接暂不可用，请重新打开设置");}});}
    @Override public void onServiceDied(XposedService s){runOnUiThread(()->{if(isDestroyed())return;service=null;restartButton.setEnabled(false);status.setText("框架未连接；请在 LSPosed 启用模块");});}
    private final class Preview extends View{
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);final RingPainter painter=new RingPainter();
        final BatteryMotion[] motions={new BatteryMotion(),new BatteryMotion(),new BatteryMotion()};
        final float[] positions=new float[3];boolean flipped,framePosted;int lastStyle=-1;
        final Runnable frame=this::advance;
        Preview(){
            super(MainActivity.this);GradientDrawable bg=new GradientDrawable();bg.setColor(0xff142033);bg.setCornerRadius(dp(16));setBackground(bg);
            setContentDescription("点击预览充电切换动画");setOnClickListener(v->{flipped=!flipped;sync();invalidate();});
        }
        boolean charging(int index){return index==0?flipped:!flipped;}
        void sync(){
            long now=SystemClock.uptimeMillis();boolean running=false;
            boolean animate=lastStyle==config.style&&isShown()&&ValueAnimator.areAnimatorsEnabled();lastStyle=config.style;
            for(int i=0;i<3;i++){
                if(motions[i].setTarget(config.percent||charging(i),now,animate))positions[i]=motions[i].valueAt(now);
                running|=motions[i].isRunning(now);
            }
            if(running&&!framePosted){framePosted=true;postOnAnimation(frame);}
        }
        void advance(){
            framePosted=false;long now=SystemClock.uptimeMillis();boolean running=false;
            for(int i=0;i<3;i++){positions[i]=motions[i].valueAt(now);running|=motions[i].isRunning(now);}
            invalidate();if(running&&isShown()){framePosted=true;postOnAnimation(frame);}
        }
        @Override protected void onDetachedFromWindow(){removeCallbacks(frame);framePosted=false;for(int i=0;i<3;i++){motions[i].snap();positions[i]=motions[i].valueAt(SystemClock.uptimeMillis());}lastStyle=-1;super.onDetachedFromWindow();}
        @Override protected void onDraw(Canvas canvas){
            sync();
            SignalModel charging=SignalModel.demo();charging.charging=true;
            float cell=getWidth()/3f,height=Math.min(dp(86),(cell-dp(16))*132/painter.logicalWidth(config,charging));
            for(int i=0;i<3;i++){
                SignalModel model=SignalModel.demo();model.battery=86;model.charging=charging(i);model.fastCharging=i==2;
                float width=painter.logicalWidth(config,model,positions[i])*height/132;
                canvas.save();canvas.translate((i+.5f)*cell-width/2,dp(20)+(dp(86)-height)/2);painter.draw(canvas,width,height,model,config,0xffedf3fb,positions[i]);canvas.restore();
                paint.setColor(0xff9eb5ce);paint.setTextSize(dp(11));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(model.charging?(model.fastCharging?"快速充电":"普通充电"):(flipped?"已拔线":"未充电"),(i+.5f)*cell,dp(133),paint);
            }
        }
    }
}
