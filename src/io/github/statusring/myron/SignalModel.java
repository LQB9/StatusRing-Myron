package io.github.statusring.myron;

public final class SignalModel {
    // -2: no subscription in this physical slot; -1: present but not yet sampled.
    public final int[] levels={-2,-2};
    public final String[] types={"",""};
    public int dataSlot=-1, battery=-1, wifiLevel=0;
    public boolean wifi=false, charging=false, fastCharging=false, airplane=false;
    public int chargeBoltCount(){return !charging?0:fastCharging?2:1;}
    public int presentCount(){return (levels[0]!=-2?1:0)+(levels[1]!=-2?1:0);}
    public String type(){return dataSlot>=0&&dataSlot<2?types[dataSlot]:"";}
    public String typeForSlot(int slot){
        if(slot<0||slot>=2||levels[slot]==-2||airplane)return "—";
        return types[slot]==null||types[slot].isEmpty()?"—":types[slot];
    }
    public static int normalizeLevel(int n){return n<0?-1:Math.min(4,n);}
    // Android ServiceState: IN_SERVICE=0, POWER_OFF=3. Data-only WWAN service
    // remains usable when circuit-switched voice is not registered.
    public static boolean serviceAvailable(int voiceState,boolean packetRegistered){return voiceState!=3&&(voiceState==0||packetRegistered);}
    public static String networkName(int type,int override){
        if(override==3||override==4||override==5||type==20)return "5G";
        if(type==13||type==19||override==1||override==2)return "4G";
        if(type==3||type==5||type==6||type==8||type==9||type==10||type==12||type==14||type==15||type==17)return "3G";
        if(type==1||type==2||type==4||type==7||type==11||type==16)return "2G";
        return "";
    }
    public static SignalModel demo(){
        SignalModel m=new SignalModel();m.levels[0]=3;m.levels[1]=2;m.dataSlot=0;m.types[0]="5G";m.types[1]="4G";
        m.battery=86;m.wifi=true;m.wifiLevel=4;return m;
    }
    public String describe(){
        String s="电量"+(battery<0?"未知":battery+"%")+(charging?(fastCharging?"，快速充电中":"，普通充电中"):"")+"，"+(wifi?"Wi-Fi "+wifiLevel+"格":"Wi-Fi未连接");
        for(int i=0;i<2;i++)s+="，卡"+(i+1)+(levels[i]==-2?"未启用":(types[i].isEmpty()?"":types[i]+"，")+(levels[i]<0?"信号未知":"信号"+levels[i]+"格"));
        return s+(airplane?"，飞行模式":"");
    }
}
