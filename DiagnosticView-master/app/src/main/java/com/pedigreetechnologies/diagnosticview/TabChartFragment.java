package com.pedigreetechnologies.diagnosticview;

import android.content.res.Resources;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class TabChartFragment extends Fragment {

    //Singleton containing all graph data
    private AllGraphDataSingleton allGraphDataSingleton;
    //Parameters to be displayed, selected in previous activity
    private ArrayList<DiagnosticParameter> selectedParameterList;
    //Contains the location of the graphs stored in lineChartArrayList by name
    private HashMap<String, Integer> graphIndexMap;
    //Data entries for the graphs
    private ArrayList<ArrayList<Entry>> dataEntries;
    //Contains the charts so the parents don't need to be searched to find the graphs
    private ArrayList<LineChart> lineChartArrayList;
    //Parent layout that contains the graphs
    private LinearLayout graphLinearLayout;
    private long referenceTime;

    //Handler and thread to update the graphs
    private Handler timerHandler;

    //Max graph scale in # of milliseconds 5 minutes * 60 seconds * 1000 to m/s
    long graphMax = 1 * 30 * 1000;

    // Number of updates per second ex: 1000/4 = approx 4 times a second
    private int updateTime = 1000 / 2;

    //Issue with graph crashing when there is no data in the set, this is removed after the graph has more data
    Entry emptyEntryPlaceholder = (new Entry(0, 0));

    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        if (container == null) {
            // We have different layouts, and in one of them this
            // fragment's containing frame doesn't exist.  The fragment
            // may still be created from its saved state, but there is
            // no reason to try to create its view hierarchy because it
            // won't be displayed.  Note this is not needed -- we could
            // just run the code below, where we would create and return
            // the view hierarchy; it would just never be used.
            return null;
        }

        ScrollView scrollView = (ScrollView)inflater.inflate(R.layout.tab_chart_layout, container, false);

        allGraphDataSingleton = AllGraphDataSingleton.getInstance();
        dataEntries = new ArrayList<>();
        lineChartArrayList = new ArrayList<>();
        graphLinearLayout = (LinearLayout)scrollView.findViewById(R.id.graphListLayout);
        graphIndexMap = new HashMap<>();

        timerHandler = new Handler();
        timerHandler.postDelayed(new Runnable(){
            public void run(){
                updateGraphs();
                timerHandler.postDelayed(this, updateTime);
            }
        }, updateTime);

        //Get the parameters from the parent activity
        Bundle extras = getArguments();
        if (extras != null) {
            selectedParameterList = extras.getParcelableArrayList("selectedParameterList");
        }

        //Build the initial graphs from the selected parameters
        if(selectedParameterList != null){
            long currentTime = System.currentTimeMillis();
            SensorDataPoints[] latestValues;
            String dataLabel = "";
            SensorDataPoints dataPoint;

            //Reset the map that keeps track of the data already displayed
            allGraphDataSingleton.resetLastIndexReadMap();

            for(int i = 0; i < selectedParameterList.size(); i++){
                //Keep track of the graph index so you can find the data later
                graphIndexMap.put(selectedParameterList.get(i).getLabel(), i);
                dataEntries.add(new ArrayList<Entry>());

                //Keep track of the values added to the graph
                int entryCount = 0;

                //Get the data from the given graph label
                dataLabel = selectedParameterList.get(i).getLabel();
                latestValues = allGraphDataSingleton.getDataForUpdatePeriod(dataLabel, currentTime);

                //Add all values to the graphs
                for(int j = 0; j < latestValues.length; j++){
                    dataPoint = latestValues[j];
                    dataEntries.get(i).add(new Entry( dataPoint.getTime(),dataPoint.getDataPoint()));
                    entryCount++;
                }

                //If no entries were added to the graph add a placeholder value to stop the graph from crashing
                if(entryCount == 0){
                    dataEntries.get(i).add(emptyEntryPlaceholder);
                }
                Log.v("ToStrings", selectedParameterList.get(i).toString());
            }
            createGraphs(currentTime);
        }

        return scrollView;
    }

    private void updateGraphs(){
        LineChart chart;
        LineData lineData;
        ILineDataSet set;
        SensorDataPoints[] latestValues;
        long currentTime = System.currentTimeMillis();
        String dataLabel = "";
        int graphIndex = 0;
        SensorDataPoints dataPoint;
        int numberOfNewValues;

        long adjustedTime = currentTime - referenceTime;

        //Go through every graph and update the data if it is present
        for(int i = 0; i < selectedParameterList.size(); i++){
            //Get current graph label
            dataLabel = selectedParameterList.get(i).getLabel();
            //
            latestValues = allGraphDataSingleton.getDataForUpdatePeriod(dataLabel, currentTime);

            //Get the index of the current graph in the parent view
            graphIndex = graphIndexMap.get(dataLabel);
            //Get the graph to update
            chart = lineChartArrayList.get(graphIndex);

            //Get the data to update
            lineData = chart.getLineData();
            set = lineData.getDataSetByIndex(0);

            numberOfNewValues = latestValues.length;

            if(numberOfNewValues > 0) {
                //Remove the placeholder if it is present
                set.removeEntry(emptyEntryPlaceholder);

                //Add all of the new values to the dataset
                for (int j = 0; j < numberOfNewValues; j++) {
                    dataPoint = latestValues[j];
                    set.addEntry(new Entry(dataPoint.getTime(), dataPoint.getDataPoint()));
                }
            }

            //Update axis to move the graphs in realtime even if there are no new data points
            XAxis xAxis = chart.getXAxis();
            xAxis.setAxisMaximum(adjustedTime);
            xAxis.setAxisMinimum(adjustedTime - graphMax);

            YAxis leftYAxis = chart.getAxisLeft();

            float maxY = allGraphDataSingleton.getMaxYValue(dataLabel, currentTime, graphMax);
            //new
            maxY = formulateMaxY(maxY);

            if(!Float.isNaN(maxY) && selectedParameterList.get(i).getMin() != Double.NaN){
                leftYAxis.setAxisMaximum(maxY);
            }

            //Tell the graph and datasets that they are updated
            lineData.notifyDataChanged();
            chart.notifyDataSetChanged();

            //Refresh graph data
            chart.invalidate();
        }
    }

    private void createGraphs(long currentTime){
        //Create a graph for each of the chosen parameters
        referenceTime = allGraphDataSingleton.getReferenceStartTime();
        long adjustedTime = currentTime - referenceTime;
        TimeAxisValueFormatter axisValueFormatter = new TimeAxisValueFormatter(referenceTime);

        for(int i = 0; i < selectedParameterList.size(); i++){

            LineChart lineChart = new LineChart(this.getContext());

            //Setting view width and height, will be need to be used for dynamic graph size
            Resources r = getResources();
            float px = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 285, r.getDisplayMetrics());
            lineChart.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int)px));

            //Add graph to parent view (Linear layout)
            lineChartArrayList.add(lineChart);
            graphLinearLayout.addView(lineChart);

            //Get the variables for creating the graph
            DiagnosticParameter parameter = selectedParameterList.get(i);
            String label = parameter.getLabel();
            double min  = parameter.getMin();
            double max = parameter.getMax();

            //Create a dataset for the graph
            List<Entry> entries = dataEntries.get(i);
            LineDataSet dataSet = new LineDataSet(entries, label);
            dataSet.setDrawCircles(false);
            dataSet.setLineWidth(3f);//was 1.5
            dataSet.setDrawValues(false);
            dataSet.setColor(Color.parseColor(getColorI(i)));
            LineData lineData = new LineData(dataSet);

            //Change graph parameters
            lineChart.setTouchEnabled(false);
            lineChart.setDragEnabled(false);//was false
            lineChart.setPinchZoom(false);
            lineChart.setDrawGridBackground(false);
            lineChart.setAutoScaleMinMaxEnabled(false);
            Description description = lineChart.getDescription();
            description.setEnabled(false);

            //Disabling right Y Axis labels
            YAxis rightYAxis = lineChart.getAxisRight();
            rightYAxis.setEnabled(false);

            YAxis leftYAxis = lineChart.getAxisLeft();
            leftYAxis.setPosition(YAxis.YAxisLabelPosition.OUTSIDE_CHART);
            //Showing only max and min
            leftYAxis.setLabelCount(5, true);
            //If the parameter has a min and max set the Left Y Axis to the min and max
            if(!Double.isNaN(min) && !Double.isNaN(max))
            {
                leftYAxis.setAxisMinimum((float)min);

                float maxY = allGraphDataSingleton.getMaxYValue(label, currentTime, graphMax);
                //new
                maxY = formulateMaxY(maxY);

                if(!Float.isNaN(maxY) && selectedParameterList.get(i).getMin() != Double.NaN){
                    leftYAxis.setAxisMaximum(maxY);
                }
            }
            //Set the xAxis, max is the currentTime and the min is the current minus the total max displayed
            XAxis xAxis = lineChart.getXAxis();
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setAxisMaximum(adjustedTime);
            xAxis.setAxisMinimum(adjustedTime - graphMax);
            xAxis.setValueFormatter(axisValueFormatter);

            //After graph setup add the data
            lineChart.setData(lineData);

            //Refresh graph data
            lineChart.invalidate();
        }
    }

    private String getColorI(int i){
        String [] colors = {"red","blue","green","aqua","fuchsia","lime",
                            "maroon","navy","olive","silver","purple","teal"};
        int n = 12;
        return colors[i%n];
    }

    private float formulateMaxY(float max){
        float temp = max;
        temp += max / 4;
        if(temp <= 40)return temp;
        //round to nearest multiple of 10
        temp = (float) Math.ceil(temp/ 40) * 40;
        return temp;
    }
}
