package com.liskovsoft.leanbackassistant.channels;

import android.annotation.TargetApi;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build.VERSION;
import com.liskovsoft.leanbackassistant.media.ClipService;
import com.liskovsoft.leanbackassistant.media.ClipServiceCached;
import com.liskovsoft.leanbackassistant.media.Playlist;
import com.liskovsoft.leanbackassistant.recommendations.RecommendationsProvider;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.sharedutils.prefs.GlobalPreferences;

import java.util.concurrent.TimeUnit;

/**
 * JobScheduler task to synchronize the TV provider database with the desired list of channels and
 * programs. This sample app runs this once at install time to publish an initial set of channels
 * and programs, however in a real-world setting this might be run at other times to synchronize
 * a server's database with the TV provider database.
 * This code will ensure that the channels from "SampleClipApi.getDesiredPublishedChannelSet()"
 * appear in the TV provider database, and that these and all other programs are synchronized with
 * TV provider database.
 */

@TargetApi(21)
public class SynchronizeDatabaseJobService extends JobService {
    private SynchronizeDatabaseTask mSynchronizeDatabaseTask;
    private static final String TAG = SynchronizeDatabaseJobService.class.getSimpleName();
    private static boolean sInProgress;

    static void schedule(Context context) {
        if (VERSION.SDK_INT >= 23) {
            Log.d(TAG, "Registering Channels update job...");
            JobScheduler scheduler = context.getSystemService(JobScheduler.class);

            //// run one-shot job
            //scheduler.schedule(
            //        new JobInfo.Builder(0, new ComponentName(context, SynchronizeDatabaseJobService.class))
            //                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            //                .setRequiresDeviceIdle(false)
            //                .setRequiresCharging(false)
            //                .setMinimumLatency(1)
            //                .setOverrideDeadline(1)
            //                .build());

            // setup scheduled job
            scheduler.schedule(
                    new JobInfo.Builder(1, new ComponentName(context, SynchronizeDatabaseJobService.class))
                            .setPeriodic(TimeUnit.MINUTES.toMillis(30))
                            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                            .setRequiresDeviceIdle(false)
                            .setRequiresCharging(false)
                            .build());
        }
    }

    @Override
    public boolean onStartJob(JobParameters jobParameters) {
        if (sInProgress) {
            Log.d(TAG, "Channels update job already in progress. Exiting...");
            return true;
        }

        Log.d(TAG, "Starting Channels update job...");

        sInProgress = true;

        mSynchronizeDatabaseTask = new SynchronizeDatabaseTask(this, jobParameters);
        // NOTE: fetching channels in background
        mSynchronizeDatabaseTask.execute();

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters jobParameters) {
        if (mSynchronizeDatabaseTask != null) {
            mSynchronizeDatabaseTask.cancel(true);
            mSynchronizeDatabaseTask = null;
        }

        sInProgress = false;

        return true;
    }

    /**
     * Publish any default channels not already published.
     */
    private class SynchronizeDatabaseTask extends AsyncTask<Void, Void, Exception> {
        private final GlobalPreferences mPrefs;
        private final ClipService mService;
        private Context mContext;
        private JobParameters mJobParameters;

        SynchronizeDatabaseTask(Context context, JobParameters jobParameters) {
            mContext = context;
            mJobParameters = jobParameters;
            mPrefs = GlobalPreferences.instance(mContext);
            mService = ClipServiceCached.instance(mContext);
        }

        @Override
        protected Exception doInBackground(Void... params) {
            Log.d(TAG, "Syncing channels...");

            updateChannels();
            updateRecommendations();

            return null;
        }

        private void updateChannels() {
            if (Helpers.isATVChannelsSupported(mContext)) {
                try {
                    updateOrPublishChannel(mService.getSubscriptionsPlaylist());
                    updateOrPublishChannel(mService.getRecommendedPlaylist());
                    updateOrPublishChannel(mService.getHistoryPlaylist());
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        private void updateRecommendations() {
            if (Helpers.isATVRecommendationsSupported(mContext)) {
                try {
                    Playlist playlist = null;
                    switch (mPrefs.getRecommendedPlaylistType()) {
                        case GlobalPreferences.PLAYLIST_TYPE_RECOMMENDATIONS:
                            playlist = mService.getRecommendedPlaylist();
                            break;
                        case GlobalPreferences.PLAYLIST_TYPE_SUBSCRIPTIONS:
                            playlist = mService.getSubscriptionsPlaylist();
                            break;
                        case GlobalPreferences.PLAYLIST_TYPE_HISTORY:
                            playlist = mService.getHistoryPlaylist();
                            break;
                    }

                    updateOrPublishRecommendations(playlist);
                } catch (Exception e) {
                    Log.e(TAG, e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        private void updateOrPublishRecommendations(Playlist playlist) {
            if (checkPlaylist(playlist)) {
                RecommendationsProvider.createOrUpdateRecommendations(mContext, playlist);
            }
        }

        private void updateOrPublishChannel(Playlist playlist) {
            if (checkPlaylist(playlist)) {
                ChannelsProvider.createOrUpdateChannel(mContext, playlist);
            }
        }

        @Override
        protected void onPostExecute(Exception e) {
            if (e != null) {
                Log.e(TAG, "Oops. Exception while syncing: " + e.getMessage());
            } else {
                Log.d(TAG, "Channels synced successfully.");
            }

            sInProgress = false;
            mSynchronizeDatabaseTask = null;
            jobFinished(mJobParameters, false);
        }

    }

    private boolean checkPlaylist(Playlist playlist) {
        return playlist != null && playlist.getClips() != null && playlist.getClips().size() > 0;
    }
}
