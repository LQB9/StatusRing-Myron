import io.github.statusring.myron.Config;
import io.github.statusring.myron.SignalModel;
import io.github.statusring.myron.BatteryMotion;

public class CoreChecks {
    static int passed;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);passed++;}
    public static void main(String[] args){
        SignalModel model=SignalModel.demo();
        model.dataSlot=1;
        check(model.type().equals("4G")&&model.levels[0]==3&&model.levels[1]==2,"Switching data SIM keeps physical-slot readings");
        model.levels[0]=-2;
        check(model.presentCount()==1&&model.levels[1]==2,"Only physical SIM 2 enabled");
        model.levels[1]=-1;
        check(model.describe().contains("信号未知")&&!model.describe().contains("满格"),"Unknown signal is not invented");
        check(SignalModel.networkName(13,3).equals("5G"),"5G NSA overlay on LTE bearer");
        check(SignalModel.networkName(20,0).equals("5G"),"5G SA bearer");
        check(SignalModel.networkName(13,0).equals("4G"),"LTE without override");
        check(SignalModel.networkName(0,0).isEmpty(),"Unknown network type remains unknown");
        check(SignalModel.normalizeLevel(-99)==-1&&SignalModel.normalizeLevel(99)==4,"Vendor out-of-range levels");
        Config config=new Config();
        check(config.accepts(0)&&config.accepts(1)&&!config.accepts(3),"AOD and unsupported hosts are excluded");
        config.lockscreen=false;
        check(!config.accepts(1)&&config.accepts(0),"Lock-screen preference is scoped");
        config.enabled=false;
        check(!config.accepts(0)&&!config.accepts(1),"Disabled retains stock UI");
        check(Config.clamp(-200,16,28)==16&&Config.clamp(200,16,28)==28,"Bounded status bar size");
        SignalModel separate=SignalModel.demo();
        check(separate.typeForSlot(0).equals("5G")&&separate.typeForSlot(1).equals("4G"),"Network type is independent per physical SIM");
        separate.dataSlot=1;
        check(separate.typeForSlot(0).equals("5G")&&separate.typeForSlot(1).equals("4G"),"Data SIM switch cannot copy its type onto the other SIM");
        separate.types[1]="";
        check(separate.typeForSlot(1).equals("—")&&separate.typeForSlot(0).equals("5G"),"Unknown secondary RAT does not invent 4G or 5G");
        separate.airplane=true;
        check(separate.typeForSlot(0).equals("—"),"Airplane mode cannot retain a stale 5G label");
        check(separate.typeForSlot(-1).equals("—")&&separate.typeForSlot(2).equals("—"),"Invalid physical slot never accesses another SIM");
        SignalModel charge=SignalModel.demo();
        check(charge.chargeBoltCount()==0,"Unplugged shows no lightning");
        charge.charging=true;
        check(charge.chargeBoltCount()==1&&charge.describe().contains("普通充电"),"Ordinary charging has one lightning");
        charge.fastCharging=true;
        check(charge.chargeBoltCount()==2&&charge.describe().contains("快速充电"),"System fast-charging state has two lightnings");
        charge.charging=false;
        check(charge.chargeBoltCount()==0&&!charge.describe().contains("充电中"),"Unplugging clears even a stale fast-charge flag");
        check(new Config().batteryText==36,"Battery text has a larger independent default");
        check(SignalModel.serviceAvailable(1,true),"Packet-only cellular service is not shown as out of service");
        check(SignalModel.serviceAvailable(0,false),"Voice-only registration remains in service");
        check(!SignalModel.serviceAvailable(3,true),"Powered-off radio ignores a stale packet registration");
        check(!SignalModel.serviceAvailable(2,false),"Emergency-only without packet registration remains out of service");
        check(new Config().rightBatteryText==46,"Right-hand battery number has its own larger default");
        BatteryMotion motion=new BatteryMotion();
        motion.setTarget(false,0,true);
        check(motion.valueAt(0)==0&&!motion.isRunning(0),"First attachment does not animate from a false state");
        motion.setTarget(true,100,true);
        check(motion.valueAt(100)==0&&motion.isRunning(100),"Plugging starts at the existing inside position");
        check(motion.valueAt(240)>0&&motion.valueAt(240)<1,"Motion has intermediate positions");
        check(motion.valueAt(380)==1&&!motion.isRunning(380),"Motion completes and stops requesting frames");
        check(!motion.setTarget(true,500,true)&&motion.valueAt(500)==1,"Battery level callbacks do not restart settled motion");
        motion.setTarget(false,600,true);float reversing=motion.valueAt(670);motion.setTarget(true,670,true);
        check(Math.abs(motion.valueAt(670)-reversing)<.0001f&&motion.valueAt(1000)==1,"Quick replug reverses continuously and finishes");
        motion.setTarget(false,1100,false);
        check(motion.valueAt(1100)==0&&!motion.isRunning(1100),"Disabled or hidden animations snap to their target");
        BatteryMotion initialCharge=new BatteryMotion();initialCharge.setTarget(true,0,true);
        check(initialCharge.valueAt(0)==1&&!initialCharge.isRunning(0),"Booting while charging does not play a fake plug animation");
        System.out.println("PASS "+passed+" behavior checks");
    }
}
