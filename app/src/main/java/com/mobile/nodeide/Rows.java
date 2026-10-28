package com.mobile.nodeide;
import android.graphics.Color;
import android.view.*;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.*;

class Rows extends RecyclerView.Adapter<Rows.Holder> {
    interface Click { void onClick(int position); }
    final List<String> items=new ArrayList<>(); final Click click;
    Rows(Click c) {click=c;}
    void set(List<String> data){items.clear();items.addAll(data);notifyDataSetChanged();}
    public Holder onCreateViewHolder(ViewGroup p,int type){ TextView t=new TextView(p.getContext()); t.setTextColor(Color.rgb(225,222,239)); t.setTextSize(15); t.setPadding(12,16,8,16); t.setBackgroundColor(Color.rgb(43,41,76)); return new Holder(t); }
    public void onBindViewHolder(Holder h,int pos){h.text.setText(items.get(pos)); h.text.setOnClickListener(v->{int n=h.getBindingAdapterPosition();if(n!=RecyclerView.NO_POSITION)click.onClick(n);});}
    public int getItemCount(){return items.size();}
    static class Holder extends RecyclerView.ViewHolder { TextView text; Holder(TextView v){super(v);text=v;} }
}
