package com.aura.launcher.data

import com.aura.launcher.R

/** The widget's name in Settings › Widgets. The enum itself lives in luna-core, which has no resources. */
val HomeWidget.titleRes: Int
    get() = when (this) {
        HomeWidget.CLOCK -> R.string.widget_clock_title
        HomeWidget.DATE -> R.string.widget_date_title
        HomeWidget.BATTERY -> R.string.widget_battery_title
        HomeWidget.WEATHER -> R.string.widget_weather_title
        HomeWidget.TIMER -> R.string.widget_timer_title
        HomeWidget.TODO -> R.string.widget_todo_title
        HomeWidget.NOW_PLAYING -> R.string.widget_now_playing_title
        HomeWidget.CALENDAR -> R.string.widget_calendar_title
        HomeWidget.NOTIFICATIONS -> R.string.widget_notifications_title
        HomeWidget.MAP -> R.string.widget_map_title
        HomeWidget.NOW -> R.string.widget_now_title
        HomeWidget.SKY -> R.string.widget_sky_title
    }

/** The line under the name. */
val HomeWidget.subtitleRes: Int
    get() = when (this) {
        HomeWidget.CLOCK -> R.string.widget_clock_subtitle
        HomeWidget.DATE -> R.string.widget_date_subtitle
        HomeWidget.BATTERY -> R.string.widget_battery_subtitle
        HomeWidget.WEATHER -> R.string.widget_weather_subtitle
        HomeWidget.TIMER -> R.string.widget_timer_subtitle
        HomeWidget.TODO -> R.string.widget_todo_subtitle
        HomeWidget.NOW_PLAYING -> R.string.widget_now_playing_subtitle
        HomeWidget.CALENDAR -> R.string.widget_calendar_subtitle
        HomeWidget.NOTIFICATIONS -> R.string.widget_notifications_subtitle
        HomeWidget.MAP -> R.string.widget_map_subtitle
        HomeWidget.NOW -> R.string.widget_now_subtitle
        HomeWidget.SKY -> R.string.widget_sky_subtitle
    }
