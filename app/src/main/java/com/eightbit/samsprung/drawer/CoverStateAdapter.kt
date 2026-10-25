/*
 * ====================================================================
 * Copyright (c) 2021-2023 AbandonedCart.  All rights reserved.
 *
 * See https://github.com/SamSprung/.github/blob/main/LICENSE#L5
 * ====================================================================
 *
 * The license and distribution terms for any publicly available version or
 * derivative of this code cannot be changed.  i.e. this code cannot simply be
 * copied and put under another distribution license
 * [including the GNU Public License.] Content not subject to these terms is
 * subject to to the terms and conditions of the Apache License, Version 2.0.
 */

package com.eightbit.samsprung.drawer

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter

/**
 * Pager pages: 0 = notifications, 1 = app drawer and from 2 on one page per widget.
 *
 * The "fragmentID" arguments are page positions. Every widget page also has a stable item id
 * that is never reused, so the pager cannot mix up a removed page with a newly added one.
 */
class CoverStateAdapter(fragmentActivity: AppCompatActivity) : FragmentStateAdapter(fragmentActivity) {
    private val mFragments: ArrayList<PanelViewFragment> = arrayListOf()
    private val mItemIds: ArrayList<Long> = arrayListOf()
    private var nextItemId = FIRST_WIDGET_POSITION.toLong()
    private val noticeList = NotificationFragment()
    private val appDrawer = AppDrawerFragment()

    fun addFragment() : Int {
        val fragment = PanelViewFragment()
        val position = mItemIds.size + FIRST_WIDGET_POSITION
        mFragments.add(fragment)
        mItemIds.add(nextItemId++)
        notifyItemInserted(position)
        return position
    }

    fun removeFragment(fragmentID: Int) {
        val index = fragmentID - FIRST_WIDGET_POSITION
        if (index !in mFragments.indices) return
        mFragments.removeAt(index)
        mItemIds.removeAt(index)
        notifyItemRemoved(fragmentID)
    }

    /**
     * Removes every widget page, leaving only the notifications and the app drawer.
     */
    fun clearFragments() {
        val count = mFragments.size
        if (count == 0) return
        mFragments.clear()
        mItemIds.clear()
        notifyItemRangeRemoved(FIRST_WIDGET_POSITION, count)
    }

    fun getFragment(fragmentID: Int) : PanelViewFragment {
        return mFragments[fragmentID - FIRST_WIDGET_POSITION]
    }

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> noticeList
            1 -> appDrawer
            else -> mFragments[position - FIRST_WIDGET_POSITION]
        }
    }

    override fun getItemId(position: Int): Long {
        return if (position < FIRST_WIDGET_POSITION) position.toLong()
        else mItemIds[position - FIRST_WIDGET_POSITION]
    }

    override fun getItemCount(): Int {
        return mItemIds.size + FIRST_WIDGET_POSITION
    }

    override fun containsItem(itemId: Long): Boolean {
        return itemId < FIRST_WIDGET_POSITION || mItemIds.contains(itemId)
    }

    companion object {
        private const val FIRST_WIDGET_POSITION = 2
    }
}
