package moe.fuqiuluo.portal.ui.mock

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import moe.fuqiuluo.portal.R

class HistoricalLocationAdapter(
    private val dataSet: MutableList<HistoricalLocation>,
    private val onItemClicked: (HistoricalLocation, isLongClicked: Boolean) -> Unit
): RecyclerView.Adapter<HistoricalLocationAdapter.ViewHolder>() {
    class ViewHolder(
        val root: View
    ) : RecyclerView.ViewHolder(root) {
        val locationName: MaterialTextView = root.findViewById(R.id.history_location_name)
        val locationLatLon: MaterialTextView = root.findViewById(R.id.history_location_latlon)
        val locationAddress: MaterialTextView = root.findViewById(R.id.history_location_address)
        val currentBadge: TextView = root.findViewById(R.id.history_location_current)
        val iconCard: MaterialCardView = root.findViewById(R.id.history_location_icon_card)
    }

    /** The location currently set as the fixed point; highlighted in the list. */
    var selected: HistoricalLocation? = null
        set(value) {
            val previous = field
            field = value
            dataSet.indexOf(previous).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
            dataSet.indexOf(value).takeIf { it >= 0 }?.let { notifyItemChanged(it) }
        }

    operator fun get(position: Int): HistoricalLocation {
        return dataSet[position]
    }

    fun removeItem(position: Int): HistoricalLocation {
        val removed = dataSet.removeAt(position)
        notifyItemRemoved(position)
        notifyItemRangeChanged(position, itemCount - position)
        return removed
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.layout_history_location_item, parent, false)

        return ViewHolder(view)
    }

    override fun getItemCount(): Int {
        return dataSet.size
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val location = dataSet[position]
        val context = holder.root.context
        val isSelected = location == selected

        holder.locationName.text = location.name
        holder.locationLatLon.text = context.getString(
            R.string.v2_coordinates_format,
            location.lat,
            location.lon
        )
        holder.locationAddress.text = location.address.ifBlank { context.getString(R.string.v2_no_address) }
        holder.currentBadge.visibility = if (isSelected) View.VISIBLE else View.GONE
        holder.iconCard.setCardBackgroundColor(
            ColorStateList.valueOf(
                ContextCompat.getColor(context, if (isSelected) R.color.primary else R.color.portal_mock_off)
            )
        )
        holder.root.contentDescription = if (isSelected) {
            context.getString(R.string.v2_saved_location_selected_description, location.name)
        } else {
            location.name
        }
        holder.root.setOnClickListener {
            holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?.let { onItemClicked(dataSet[it], false) }
        }
        holder.root.setOnLongClickListener {
            holder.adapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                ?.let { onItemClicked(dataSet[it], true) }
            true
        }
    }
}
