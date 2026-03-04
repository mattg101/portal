package net.gsantner.markor.widget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

import net.gsantner.markor.R;
import net.gsantner.markor.portal.PortalActions;
import net.gsantner.markor.portal.PortalEntryActivity;

public class PortalWidgetProvider extends AppWidgetProvider {
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        int requestCode = 200;
        final int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        for (int appWidgetId : appWidgetIds) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.portal_widget_layout);

            Intent media = new Intent(context, PortalEntryActivity.class).setAction(PortalActions.ACTION_MEDIA);
            Intent mic = new Intent(context, PortalEntryActivity.class).setAction(PortalActions.ACTION_AUDIO);
            Intent text = new Intent(context, PortalEntryActivity.class).setAction(PortalActions.ACTION_TEXT);

            views.setOnClickPendingIntent(R.id.portal_widget_media, PendingIntent.getActivity(context, requestCode++, media, flags));
            views.setOnClickPendingIntent(R.id.portal_widget_mic, PendingIntent.getActivity(context, requestCode++, mic, flags));
            views.setOnClickPendingIntent(R.id.portal_widget_text, PendingIntent.getActivity(context, requestCode++, text, flags));

            appWidgetManager.updateAppWidget(appWidgetId, views);
        }
        super.onUpdate(context, appWidgetManager, appWidgetIds);
    }
}
