package lt.gfau.se.shuriken.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter

class MainPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 5

    override fun createFragment(position: Int): Fragment = when (position) {
        0 -> DashboardFragment()
        1 -> NmeaFragment()
        2 -> SerialInputFragment()
        3 -> MtpFilesFragment()
        4 -> WigleFragment()
        else -> throw IllegalArgumentException("Invalid tab position: $position")
    }
}
