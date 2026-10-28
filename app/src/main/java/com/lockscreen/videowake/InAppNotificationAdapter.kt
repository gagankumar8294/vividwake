package com.lockscreen.videowake

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class InAppNotificationAdapter(
    private var list: List<InAppNotification>,
    private val onItemClick: (InAppNotification) -> Unit
) : RecyclerView.Adapter<InAppNotificationAdapter.ViewHolder>() {

    private val timeFormat = SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault())

    fun updateList(newList: List<InAppNotification>) {
        list = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_inapp_notification, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = list[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = list.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle: TextView = itemView.findViewById(R.id.tvNotifTitle)
        private val tvBody: TextView = itemView.findViewById(R.id.tvNotifBody)
        private val tvTime: TextView = itemView.findViewById(R.id.tvNotifTime)
        private val viewUnreadDot: View = itemView.findViewById(R.id.viewUnreadDot)
        private val tvActionHint: TextView = itemView.findViewById(R.id.tvActionHint)

        fun bind(item: InAppNotification) {
            tvTitle.text = item.title
            tvBody.text = item.message
            tvTime.text = timeFormat.format(Date(item.timestamp))

            viewUnreadDot.visibility = if (item.isRead) View.GONE else View.VISIBLE
            tvActionHint.visibility = if (!item.wallpaperId.isNullOrBlank()) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                onItemClick(item)
            }
        }
    }
}
