package lt.gfau.se.shuriken.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import lt.gfau.se.shuriken.databinding.ItemSerialPortBinding
import lt.gfau.se.shuriken.model.SerialDevicePort

class SerialPortAdapter(
    private val ports: List<SerialDevicePort>,
    private val onSelect: (SerialDevicePort) -> Unit
) : RecyclerView.Adapter<SerialPortAdapter.ViewHolder>() {

    inner class ViewHolder(private val binding: ItemSerialPortBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(port: SerialDevicePort) {
            binding.tvPortName.text = port.displayName
            binding.tvPortDetails.text = port.displayDetail
            binding.root.setOnClickListener { onSelect(port) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSerialPortBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) =
        holder.bind(ports[position])

    override fun getItemCount(): Int = ports.size
}
