/**
 * Copyright (c) 2012 Todoroo Inc
 *
 * See the file "LICENSE" for the full license governing this code.
 */
package com.todoroo.astrid.ui;

import java.util.LinkedList;
import java.util.List;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ToggleButton;

import com.timsu.astrid.R;
import com.todoroo.astrid.data.Task;
import com.todoroo.astrid.helper.TaskEditControlSet;

/**
 * Control Set for setting task importance
 *
 * @author Tim Su <tim@todoroo.com>
 *
 */
public class ImportanceControlSet extends TaskEditControlSet {
    private final List<CompoundButton> buttons = new LinkedList<CompoundButton>();
    private final int[] colors;
    private final List<ImportanceChangedListener> listeners = new LinkedList<ImportanceChangedListener>();

    public interface ImportanceChangedListener {
        public void importanceChanged(int i, int color);
    }

    public ImportanceControlSet(Activity activity, int layout) {
        super(activity, layout);
        colors = Task.getImportanceColors(activity.getResources());
    }

    public void setImportance(Integer i) {
        for(CompoundButton b : buttons) {
            if(b.getTag() == i) {
                b.setTextSize(getTextSize());
                b.setChecked(true);
                b.setBackgroundResource(R.drawable.importance_background_selected);
            } else {
                b.setTextSize(getTextSize());
                b.setChecked(false);
                b.setTextColor(colors[(Integer)b.getTag()]);
                b.setBackgroundResource(0);
            }
        }

        for (ImportanceChangedListener l : listeners) {
            l.importanceChanged(i, colors[i]);
        }
    }

    private int getTextSize() {
        return 24;
    }

    public Integer getImportance() {
        for(CompoundButton b : buttons)
            if(b.isChecked())
                return (Integer) b.getTag();
        return null;
    }

    public void addListener(ImportanceChangedListener listener) {
        listeners.add(listener);
    }

    public void removeListener(ImportanceChangedListener listener) {
        if (listeners.contains(listener))
            listeners.remove(listener);
    }

    @Override
    protected void afterInflate() {
        LinearLayout container = (LinearLayout) getView().findViewById(R.id.importance_container);

        int min = Task.IMPORTANCE_MOST;
        int max = Task.IMPORTANCE_LEAST;

        DisplayMetrics metrics = new DisplayMetrics();
        activity.getWindowManager().getDefaultDisplay().getMetrics(metrics);

        int width = (int) (metrics.widthPixels / metrics.density) - 20;
        int usedWidth = 0;

        for(int i = min; i <= max; i++) {
            final ToggleButton button = new ToggleButton(activity);
            LinearLayout.LayoutParams params;

            int dimension = 38;
            params = new LinearLayout.LayoutParams((int) (metrics.density * dimension), (int) (metrics.density * dimension));
            usedWidth += dimension;
            button.setLayoutParams(params);

            StringBuilder label = new StringBuilder();
            if (i == max)
                label.append('\u25CB');
            for(int j = Task.IMPORTANCE_LEAST - 1; j >= i; j--)
                label.append('!');

            button.setTextColor(colors[i]);
            button.setTextOff(label);
            button.setTextOn(label);
            button.setPadding(0, 1, 0, 0);

            button.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    setImportance((Integer)button.getTag());
                }
            });
            button.setTag(i);

            buttons.add(button);
            container.addView(button);
        }

        if (usedWidth > width * 2 /3 ) {
            getView().findViewById(R.id.display_row_title).setVisibility(View.GONE);
        }
    }

    @Override
    public void readFromTask(Task task) {
        super.readFromTask(task);
        setImportance(model.getValue(Task.IMPORTANCE));
    }

    // Same as above because we need the setImportance listeners to fire even in
    // the case when the UI hasn't been created yet
    @Override
    protected void readFromTaskOnInitialize() {
        setImportance(model.getValue(Task.IMPORTANCE));
    }

    @Override
    protected String writeToModelAfterInitialized(Task task) {
        if(getImportance() != null)
            task.setValue(Task.IMPORTANCE, getImportance());
        return null;
    }
}
