package com.devcompia.iapaulista.ui.monitoramento;

import androidx.lifecycle.ViewModelProvider;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.devcompia.iapaulista.R;

public class MonitoramentosFragment extends Fragment {

    private MonitoramentosViewModel mViewModel;

    public static MonitoramentosFragment newInstance() {
        return new MonitoramentosFragment();
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_monitoramentos, container, false);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        mViewModel = new ViewModelProvider(this).get(MonitoramentosViewModel.class);
        // TODO: Use the ViewModel
    }

}