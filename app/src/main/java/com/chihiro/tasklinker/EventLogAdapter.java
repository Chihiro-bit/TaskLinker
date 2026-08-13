package com.chihiro.tasklinker;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/** 事件日志列表适配器（复用系统 simple_list_item_1 布局） */
public class EventLogAdapter extends RecyclerView.Adapter<EventLogAdapter.ViewHolder> {

    private final List<String> items;

    public EventLogAdapter(List<String> items) {
        this.items = items;
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        ViewHolder(View view) {
            super(view);
            text = view.findViewById(android.R.id.text1);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(android.R.layout.simple_list_item_1, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.text.setText(items.get(position));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }
}
