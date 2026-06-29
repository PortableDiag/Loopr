package com.loopr.player

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class VideoAdapter(
    private val scope: CoroutineScope,
    private val onClick: (VideoItem, Int) -> Unit
) : ListAdapter<VideoItem, VideoAdapter.VH>(DIFF) {

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<VideoItem>() {
            override fun areItemsTheSame(a: VideoItem, b: VideoItem) = a.id == b.id
            override fun areContentsTheSame(a: VideoItem, b: VideoItem) = a == b
        }

        fun formatDuration(ms: Long): String {
            if (ms <= 0) return "0:00"
            val totalSec = ms / 1000
            val h = TimeUnit.SECONDS.toHours(totalSec)
            val m = TimeUnit.SECONDS.toMinutes(totalSec) % 60
            val s = totalSec % 60
            return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
            else String.format("%d:%02d", m, s)
        }
    }

    inner class VH(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val duration: TextView = view.findViewById(R.id.duration)
        val title: TextView = view.findViewById(R.id.title)
        val subtitle: TextView = view.findViewById(R.id.subtitle)
        var job: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.title.text = item.title
        holder.duration.text = formatDuration(item.durationMs)
        val res = if (item.height > 0) "${item.height}p · " else ""
        holder.subtitle.text = res + Formatter.formatShortFileSize(holder.itemView.context, item.sizeBytes)
        holder.itemView.setOnClickListener { onClick(item, holder.bindingAdapterPosition) }

        holder.job?.cancel()
        val cached = ThumbnailLoader.cached(item.id)
        if (cached != null) {
            holder.thumb.setImageBitmap(cached)
        } else {
            holder.thumb.setImageResource(R.drawable.ic_video)
            holder.job = scope.launch {
                val bmp = ThumbnailLoader.load(holder.itemView.context, item)
                if (bmp != null && holder.bindingAdapterPosition == position) {
                    holder.thumb.setImageBitmap(bmp)
                }
            }
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.job?.cancel()
        holder.job = null
    }
}
