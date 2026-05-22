package com.app.nosatmosphereeffect.helper

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.app.nosatmosphereeffect.R

data class EffectItem(
    val id: String,
    val title: String,
    val description: String
)

class EffectsAdapter(
    private val items: List<EffectItem>,
    private val onClick: (EffectItem) -> Unit
) : RecyclerView.Adapter<EffectsAdapter.EffectViewHolder>() {

    inner class EffectViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.textTitle)
        val desc: TextView = view.findViewById(R.id.textDescription)
        val card: View = view.findViewById(R.id.cardRoot)

        fun bind(item: EffectItem) {
            title.text = item.title
            desc.text = item.description
            card.setOnClickListener { onClick(item) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EffectViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_effect_card, parent, false)
        return EffectViewHolder(view)
    }

    override fun onBindViewHolder(holder: EffectViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size
}