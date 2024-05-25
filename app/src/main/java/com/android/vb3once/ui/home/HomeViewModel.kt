package com.android.vb3once.ui.home

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewModel : ViewModel() {

    private val _text = MutableLiveData<String>().apply {
        value = "Bienvenidos a VB3 app \n Somos Frank, Fer y Luis"
    }
    val text: LiveData<String> = _text
}