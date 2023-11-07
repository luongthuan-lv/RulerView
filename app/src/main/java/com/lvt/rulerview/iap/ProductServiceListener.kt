package com.lvt.rulerview.iap

import com.lvt.rulerview.iap.DataWrappers

/**
 * Created by Luong Thuan on 14/11/2022.
 */
interface ProductServiceListener {
    /**
     * Get a list of purchased product information
     *
     * @param listPurchaseInfo - a map with available products
     */
    fun onGetListBillingDone(listPurchaseInfo: List<DataWrappers.PurchaseInfo>)
}