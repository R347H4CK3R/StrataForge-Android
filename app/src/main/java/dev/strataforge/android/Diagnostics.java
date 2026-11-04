package dev.strataforge.android;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import java.security.Security;
import java.security.MessageDigest;
import java.security.Provider;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.util.Log;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class Diagnostics extends Application {
  private static final Object LOCK=new Object();
  private static final long MAX_LOG=512*1024;
  private static final int MAX_CRASHES=8;
  private static Context context;
  private static Thread.UncaughtExceptionHandler previous;
  @Override public void onCreate(){
    super.onCreate();
    context=getApplicationContext();
    initializeSteamCrypto();
    initializeZstd();
    previous=Thread.getDefaultUncaughtExceptionHandler();
    Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
      try{
        StringWriter sw=new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        write("FATAL",thread.getName(),sw.toString());
        writeCrash(sw.toString());
      }catch(Throwable ignored){}
      if(previous!=null)previous.uncaughtException(thread,error);
      else android.os.Process.killProcess(android.os.Process.myPid());
    });
    log("app","Process started - Android "+Build.VERSION.RELEASE+" / "+Build.SUPPORTED_ABIS[0]);
    Executors.newSingleThreadScheduledExecutor(r->{
      Thread t=new Thread(r,"strataforge-health-watchdog");t.setDaemon(true);return t;
    }).scheduleWithFixedDelay(()->RuntimeHealth.check(getApplicationContext()),30,30,TimeUnit.SECONDS);
    log("health","Live diagnostic watchdog enabled (30s checks)");
    scheduleUpload(this);
    new Thread(()->GithubDiagnostics.uploadPending(getApplicationContext()),"initial-diagnostics-upload").start();
  }
  private static void initializeZstd(){
    try{
      // Verify the Android AAR bundles both Java methods and ARM native binary.
      byte[] source="StrataForge Zstandard compatibility self test".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      byte[] packed=com.github.luben.zstd.Zstd.compress(source);
      byte[] restored=com.github.luben.zstd.Zstd.decompress(packed,source.length);
      if(!java.util.Arrays.equals(source,restored))
        throw new IllegalStateException("Zstd round-trip mismatch");
      log("crypto","Zstandard JNI compression/decompression self-test passed");
    }catch(Throwable e){
      log("crypto","Zstd runtime unavailable: "+e.getClass().getName()+": "+e.getMessage());
    }
  }
  static void scheduleUpload(Context c){
    try{
      JobScheduler scheduler=(JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
      if(scheduler!=null){
        JobInfo job=new JobInfo.Builder(11042,new ComponentName(c,DiagnosticUploadJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .setPeriodic(15*60*1000L)
            .build();
        scheduler.schedule(job);
      }
    }catch(Exception ignored){}
  }
  private static void initializeSteamCrypto(){
    try{
      // Android ships a restricted provider also named BC. JavaSteam expects the full
      // provider when it calls MessageDigest.getInstance("SHA-1", "BC").
      Provider active=Security.getProvider("BC");
      if(!(active instanceof BouncyCastleProvider)){
        if(active!=null)Security.removeProvider("BC");
        int position=Security.insertProviderAt(new BouncyCastleProvider(),1);
        if(position<0)throw new IllegalStateException("BC provider registration rejected");
      }
      MessageDigest sha=MessageDigest.getInstance("SHA-1","BC");
      byte[] digest=sha.digest("abc".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
      StringBuilder hex=new StringBuilder();
      for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
      if(!"a9993e364706816aba3e25717850c26c9cd0d89d".equals(hex.toString()))
        throw new SecurityException("BC SHA-1 self-test mismatch");
      log("crypto","Full BouncyCastle provider active; SHA-1 self-test passed");
    }catch(Throwable e){
      log("crypto","Steam cryptography provider error: "+e.getClass().getSimpleName()+": "+e.getMessage());
    }
  }
  private static String now(){
    SimpleDateFormat f=new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.US);
    f.setTimeZone(TimeZone.getTimeZone("UTC"));
    return f.format(new Date());
  }
  static File directory(Context c){File f=new File(c.getFilesDir(),"diagnostics");if(!f.exists())f.mkdirs();return f;}
  private static String strip(String message){
    if(message==null)return "";
    // Avoid persisting QR challenges and common credential/token parameters.
    String s=message.replaceAll("(?i)(access_token|refresh_token|password|steamLoginSecure|sessionid|token|authcode)=([^&\\s]+)","$1=[REDACTED]");
    return s.length()>8000?s.substring(0,8000)+" [truncated]":s;
  }
  static void log(String category,String message){write("INFO",category,message);}
  static void error(String category,Throwable e){
    write("ERROR",category,e.getClass().getSimpleName()+": "+e.getMessage());
    Context c=context;
    if(c!=null)new Thread(()->GithubDiagnostics.uploadPending(c),"health-auto-report").start();
  }
  static void write(String level,String category,String message){
    Context c=context;if(c==null)return;
    synchronized(LOCK){
      try{
        File f=new File(directory(c),"session.log");
        if(f.length()>MAX_LOG){
          File old=new File(directory(c),"session.previous.log");
          if(old.exists())old.delete();
          f.renameTo(old);
        }
        try(FileWriter out=new FileWriter(f,true)){
          out.write(now()+" ["+level+"] ["+strip(category)+"] "+strip(message).replace("\n"," | ")+"\n");
        }
      }catch(IOException ex){Log.e("StrataForge","Diagnostic write failed",ex);}
    }
  }
  private static void writeCrash(String trace){
    Context c=context;if(c==null)return;
    synchronized(LOCK){
      try{
        File dir=directory(c);
        File[] items=dir.listFiles((parent,name)->name.startsWith("crash-")&&name.endsWith(".txt"));
        if(items!=null&&items.length>=MAX_CRASHES){
          java.util.Arrays.sort(items,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
          for(int i=0;i<=items.length-MAX_CRASHES;i++)items[i].delete();
        }
        File f=new File(dir,"crash-"+System.currentTimeMillis()+".txt");
        try(FileWriter out=new FileWriter(f)){
          out.write("StrataForge Android crash\nUTC: "+now()+"\nDevice: "+Build.MANUFACTURER+" "+Build.MODEL+"\nAndroid: "+Build.VERSION.RELEASE+"\n");
          out.write(strip(trace));
        }
      }catch(Exception ignored){}
    }
  }
}
