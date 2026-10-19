package io.github.statusring.myron;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;

/** A bounded diagnostic inbox. Only this app and the SystemUI UID may write. */
public final class CompatibilityProvider extends ContentProvider {
    public static final String AUTHORITY="io.github.statusring.myron.compatibility";
    public static final Uri URI=Uri.parse("content://"+AUTHORITY);
    public static final String VERSION="1.0.14";
    @Override public boolean onCreate(){return true;}
    @Override public Bundle call(String method,String arg,Bundle extras){
        if(!"report".equals(method)||extras==null)throw new IllegalArgumentException("Unknown diagnostic operation");
        int caller=Binder.getCallingUid();boolean allowed=caller==android.os.Process.myUid();
        if(!allowed)try{allowed=caller==getContext().getPackageManager().getApplicationInfo("com.android.systemui",0).uid;}catch(Exception ignored){}
        if(!allowed)throw new SecurityException("SystemUI diagnostics only");
        String report=extras.getString("report","");
        if(report.length()>16000)throw new IllegalArgumentException("Report too large");
        getContext().getSharedPreferences("compatibility",Context.MODE_PRIVATE).edit()
            .putString("report",report).putString("version",extras.getString("version",""))
            .putInt("boot",extras.getInt("boot",-1)).putLong("time",System.currentTimeMillis()).apply();
        Bundle result=new Bundle();result.putBoolean("accepted",true);return result;
    }
    @Override public Cursor query(Uri u,String[] p,String s,String[] a,String order){throw new UnsupportedOperationException();}
    @Override public String getType(Uri u){return null;}
    @Override public Uri insert(Uri u,ContentValues v){throw new UnsupportedOperationException();}
    @Override public int delete(Uri u,String s,String[] a){throw new UnsupportedOperationException();}
    @Override public int update(Uri u,ContentValues v,String s,String[] a){throw new UnsupportedOperationException();}
}
