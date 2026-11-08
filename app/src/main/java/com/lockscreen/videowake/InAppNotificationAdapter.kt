package com.lockscreen.videowake

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
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
