package com.lockscreen.videowake

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

sealed class NotificationListItem {
    data class Header(val title: String) : NotificationListItem()
    data class Item(val notification: InAppNotification) : NotificationListItem()
}

class InAppNotificationAdapter(
    notifications: List<InAppNotification>,
    private val onItemClick: (InAppNotification) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_ITEM = 1

        fun groupNotificationsByTimeline(notifications: List<InAppNotification>): List<NotificationListItem> {
            if (notifications.isEmpty()) return emptyList()

            val sorted = notifications.sortedByDescending { it.timestamp }

            val cal = Calendar.getInstance()
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val startOfToday = cal.timeInMillis

            cal.add(Calendar.DAY_OF_YEAR, -1)
            val startOfYesterday = cal.timeInMillis

            cal.add(Calendar.DAY_OF_YEAR, -6) // 7 days ago
            val startOfThisWeek = cal.timeInMillis

            cal.timeInMillis = startOfToday
            cal.add(Calendar.DAY_OF_YEAR, -30) // 30 days ago
            val startOfLastMonth = cal.timeInMillis

            val todayList = mutableListOf<InAppNotification>()
            val yesterdayList = mutableListOf<InAppNotification>()
            val thisWeekList = mutableListOf<InAppNotification>()
            val lastMonthList = mutableListOf<InAppNotification>()
            val olderList = mutableListOf<InAppNotification>()

            for (notif in sorted) {
                when {
                    notif.timestamp >= startOfToday -> todayList.add(notif)
                    notif.timestamp >= startOfYesterday -> yesterdayList.add(notif)
                    notif.timestamp >= startOfThisWeek -> thisWeekList.add(notif)
                    notif.timestamp >= startOfLastMonth -> lastMonthList.add(notif)
                    else -> olderList.add(notif)
                }
            }

            val result = mutableListOf<NotificationListItem>()
            if (todayList.isNotEmpty()) {
                result.add(NotificationListItem.Header("TODAY"))
                todayList.forEach { result.add(NotificationListItem.Item(it)) }
            }
            if (yesterdayList.isNotEmpty()) {
                result.add(NotificationListItem.Header("YESTERDAY"))
                yesterdayList.forEach { result.add(NotificationListItem.Item(it)) }
            }
            if (thisWeekList.isNotEmpty()) {
                result.add(NotificationListItem.Header("THIS WEEK"))
                thisWeekList.forEach { result.add(NotificationListItem.Item(it)) }
            }
            if (lastMonthList.isNotEmpty()) {
                result.add(NotificationListItem.Header("LAST MONTH"))
                lastMonthList.forEach { result.add(NotificationListItem.Item(it)) }
            }
            if (olderList.isNotEmpty()) {
                result.add(NotificationListItem.Header("OLDER"))
                olderList.forEach { result.add(NotificationListItem.Item(it)) }
            }
            return result
        }
    }

    private var items: List<NotificationListItem> = groupNotificationsByTimeline(notifications)
    private val timeFormat = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())

    fun updateNotifications(notifications: List<InAppNotification>) {
        items = groupNotificationsByTimeline(notifications)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is NotificationListItem.Header -> TYPE_HEADER
            is NotificationListItem.Item -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            val view = inflater.inflate(R.layout.item_notification_header, parent, false)
            HeaderViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_inapp_notification, parent, false)
            ItemViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is NotificationListItem.Header -> (holder as HeaderViewHolder).bind(item.title)
            is NotificationListItem.Item -> (holder as ItemViewHolder).bind(item.notification)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class HeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvHeaderTitle)

        fun bind(title: String) {
            tvTitle.text = title
        }
    }

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardNotifItem: MaterialCardView = itemView.findViewById(R.id.cardNotifItem)
        private val tvTitle: TextView = itemView.findViewById(R.id.tvNotifTitle)
        private val tvBody: TextView = itemView.findViewById(R.id.tvNotifBody)
        private val tvTime: TextView = itemView.findViewById(R.id.tvNotifTime)
        private val viewUnreadDot: View = itemView.findViewById(R.id.viewUnreadDot)
        private val tvActionHint: TextView = itemView.findViewById(R.id.tvActionHint)

        fun bind(item: InAppNotification) {
            tvTitle.text = item.title
            tvBody.text = item.message
            tvTime.text = timeFormat.format(Date(item.timestamp))

            if (item.isRead) {
                // Dimmed styling for read / previously clicked notifications
                itemView.alpha = 0.52f
                viewUnreadDot.visibility = View.GONE
                tvTitle.setTextColor(Color.parseColor("#9CA3AF"))
                tvBody.setTextColor(Color.parseColor("#6B7280"))
                tvTime.setTextColor(Color.parseColor("#4B5563"))
                cardNotifItem.setCardBackgroundColor(Color.parseColor("#120E1E"))
                cardNotifItem.strokeColor = Color.parseColor("#1F182F")
                tvActionHint.setTextColor(Color.parseColor("#6B7280"))
            } else {
                // Fully bright and prominent for unseen / unclicked notifications
                itemView.alpha = 1.0f
                viewUnreadDot.visibility = View.VISIBLE
                tvTitle.setTextColor(Color.parseColor("#FFFFFF"))
                tvBody.setTextColor(Color.parseColor("#E2E8F0"))
                tvTime.setTextColor(Color.parseColor("#94A3B8"))
                cardNotifItem.setCardBackgroundColor(Color.parseColor("#1E1730"))
                cardNotifItem.strokeColor = Color.parseColor("#4C1D95")
                tvActionHint.setTextColor(Color.parseColor("#A78BFA"))
            }

            tvActionHint.visibility = if (!item.wallpaperId.isNullOrBlank()) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                if (!item.isRead) {
                    itemView.animate().alpha(0.52f).setDuration(120).start()
                    viewUnreadDot.visibility = View.GONE
                    tvTitle.setTextColor(Color.parseColor("#9CA3AF"))
                    tvBody.setTextColor(Color.parseColor("#6B7280"))
                    cardNotifItem.setCardBackgroundColor(Color.parseColor("#120E1E"))
                    cardNotifItem.strokeColor = Color.parseColor("#1F182F")
                }
                onItemClick(item)
            }
        }
    }
}
