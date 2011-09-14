package com.axelby.podax;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.ActivityManager.RunningServiceInfo;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.RemoteViews;

public class WidgetProvider extends AppWidgetProvider {

	@Override
	public void onUpdate(Context context, AppWidgetManager appWidgetManager,
			int[] appWidgetIds) {
		Log.d("Podax", "widget onUpdate");

		updateWidget(context);

		super.onUpdate(context, appWidgetManager, appWidgetIds);
	}

	public static boolean isPlaying(Context context) {
		ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
	    for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE))
	        if (PlayerService.class.getName().equals(service.service.getClassName()))
	            return true;
	    return false;
	}

	public static void updateWidget(Context context) {
		updateWidget(context, isPlaying(context));
	}
	public static void updateWidget(Context context, boolean isPlaying) {
		DBAdapter dbAdapter = DBAdapter.getInstance(context);
		RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget);
		Podcast p = dbAdapter.loadLastPlayedPodcast();
		if (p == null)
			p = dbAdapter.getFirstInQueue();

		if (p == null) {
			views.setTextViewText(R.id.title, "Queue empty");
			views.setTextViewText(R.id.podcast, "");
			views.setTextViewText(R.id.positionstring, "");
			views.setImageViewResource(R.id.play_btn, android.R.drawable.ic_media_play);
			views.setOnClickPendingIntent(R.id.play_btn, null);
			views.setOnClickPendingIntent(R.id.show_btn, null);
		} else {
			views.setTextViewText(R.id.title, p.getTitle());
			views.setTextViewText(R.id.podcast, p.getSubscription().getDisplayTitle());
			views.setTextViewText(R.id.positionstring, PlayerService.getPositionString(p.getDuration(), p.getLastPosition()));
			int imageRes = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;
			views.setImageViewResource(R.id.play_btn, imageRes);
			
			// set up pending intents
			Intent playIntent = new Intent(context, PlayerService.class);
			playIntent.putExtra(Constants.EXTRA_PLAYER_COMMAND, Constants.PLAYER_COMMAND_PLAYPAUSE);
			PendingIntent playPendingIntent = PendingIntent.getService(context, 0, playIntent, 0);
			views.setOnClickPendingIntent(R.id.play_btn, playPendingIntent);

			Intent showIntent = new Intent(context, PodcastDetailActivity.class);
			PendingIntent showPendingIntent = PendingIntent.getActivity(context, 0, showIntent, 0);
			views.setOnClickPendingIntent(R.id.show_btn, showPendingIntent);

		}
		AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(context);
		appWidgetManager.updateAppWidget(new ComponentName(context, "com.axelby.podax.WidgetProvider"), views);
	}
}
