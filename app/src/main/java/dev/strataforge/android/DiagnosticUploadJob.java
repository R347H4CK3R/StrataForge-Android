package dev.strataforge.android;
import android.app.job.JobParameters;
import android.app.job.JobService;
public class DiagnosticUploadJob extends JobService {
  @Override public boolean onStartJob(JobParameters params){
    new Thread(()->{
      try{GithubDiagnostics.uploadPending(getApplicationContext());}
      finally{jobFinished(params,false);}
    },"github-diagnostics").start();
    return true;
  }
  @Override public boolean onStopJob(JobParameters params){return true;}
}
