/*
 * Copyright 2015 AMG.net - Politechnika Łódzka
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.amg.jira.plugins.jhz.services;

import com.atlassian.jira.charts.Chart;
import com.atlassian.jira.charts.ChartFactory;
import com.atlassian.jira.charts.jfreechart.ChartHelper;
import com.atlassian.jira.charts.jfreechart.util.ChartUtil;
import com.atlassian.jira.issue.Issue;
import com.atlassian.jira.issue.changehistory.ChangeHistoryManager;
import com.atlassian.jira.issue.history.ChangeItemBean;
import com.atlassian.jira.issue.search.SearchException;
import com.atlassian.jira.issue.search.SearchProvider;
import com.atlassian.jira.project.ProjectManager;
import com.atlassian.jira.project.version.Version;
import com.atlassian.jira.project.version.VersionManager;
import com.atlassian.jira.timezone.TimeZoneManager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.geom.Ellipse2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;

import org.apache.commons.lang.mutable.MutableInt;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.time.RegularTimePeriod;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.jfree.data.xy.XYDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.osgi.extensions.annotation.ServiceReference;
import org.springframework.stereotype.Component;

/**
 * Class responsible for generating chart using JFreeChart
 *
 * @author jarek
 */
@Component
public class JiraChartService {

    private final static Logger logger = LoggerFactory.getLogger(JiraChartService.class);

    private VersionManager versionManager;
    private SearchService searchService;
    private TimeZoneManager timeZoneManager;
    private ChangeHistoryManager changeHistoryManager;
    private ProjectManager projectManager;

    /**
     * Generates chart for Jira using JFreeChart
     *
     * @param projectName project id of filter or project
     * @param statusNames statuses of the issues to be acquired
     * @param periodName  time period on the chart
     * @param label       labels shown on the chart
     * @param dateBegin   beginning date from which chart will be drawn
     * @param width       chart width
     * @param height      chart height
     * @return chart with values
     */
    public Chart generateChart(
            final String projectName,
            final List<Set<String>> statusNames, //TODO przerobić na nową mapę z metody SearchService.getGroupedIssueTypes
            final ChartFactory.PeriodName periodName,
            final ChartFactory.VersionLabel label,
            Date dateBegin,
            final int width,
            final int height
    ) {
        List<ValueMarker> versionMarkers = getVersionMarkers(projectName, dateBegin, periodName, label);


        final Map<String, Object> params = new HashMap<String, Object>();

        final Class timePeriodClass = ChartUtil.getTimePeriodClass(periodName);

        List<Map<RegularTimePeriod, Integer>> list = generateMapsForChart(projectName, dateBegin, statusNames, timePeriodClass, timeZoneManager.getLoggedInUserTimeZone());


        Map[] dataMaps = new Map[list.size()];
        String[] seriesName = new String[list.size()];
        for (int i = 0; i < dataMaps.length; i++) {
            dataMaps[i] = list.get(i);
            seriesName[i] = SearchService.LABEL_BASE + (i + 1);
        }

        XYDataset issuesHistoryDataset = generateTimeSeries(seriesName, dataMaps);

        ChartHelper helper = new ChartHelper(org.jfree.chart.ChartFactory.createTimeSeriesChart(null, null, null, issuesHistoryDataset, true, false, false));

        XYPlot plot = (XYPlot) helper.getChart().getPlot();


        XYLineAndShapeRenderer renderer = (XYLineAndShapeRenderer) plot.getRenderer();
        renderer.setAutoPopulateSeriesStroke(false);
        renderer.setAutoPopulateSeriesShape(false);
        renderer.setBaseShapesVisible(true);
        renderer.setBaseStroke(new BasicStroke(3));
        renderer.setBaseShape(new Ellipse2D.Double(-2.0, -2.0, 4.0, 4.0));

        NumberAxis numberAxis = (NumberAxis) plot.getRangeAxis();
        numberAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());

        plot.setBackgroundPaint(Color.white);
        plot.setRangeGridlinePaint(Color.black);

        for (ValueMarker versionMarker : versionMarkers) {
            plot.addDomainMarker(versionMarker);
        }

        try {
            helper.generate(width, height);
        } catch (IOException e) {
            logger.error("Error while generating chart" + e.getMessage());
        }


        return new Chart(helper.getLocation(), helper.getImageMapHtml(), helper.getImageMapName(), params);

    }

    private XYDataset generateTimeSeries(String seriesNames[], Map[] maps) {
        TimeSeriesCollection dataset = new TimeSeriesCollection();

        TimeSeries series = null;
        for (int i = 0; i < maps.length; i++) {
            series = new TimeSeries(seriesNames[i]);
            for (Iterator iterator = maps[i].keySet().iterator(); iterator.hasNext(); ) {
                RegularTimePeriod period = (RegularTimePeriod) iterator.next();

                series.add(period, (Integer) maps[i].get(period));
            }
            dataset.addSeries(series);
        }
        return dataset;
    }

    private List<Map<RegularTimePeriod, Integer>> generateMapsForChart(String projectName, Date dateBegin, List<Set<String>> statuses, Class timePeriodClass, TimeZone timeZone) {


        List<TreeMap<RegularTimePeriod, MutableInt>> chartPeriods = new ArrayList<>(statuses.size());
        for (int i = 0; i < 5 /* TODO usunąć sztywniaka */; i++) {
            chartPeriods.add(new TreeMap<RegularTimePeriod, MutableInt>());
        }

        Date currentDate = new Date();
        List<Issue> issues = new ArrayList<>();
        List<ChangeItemBean> items;
        RegularTimePeriod timePeriod;
        try {
            issues = searchService.findAllIssues(projectName);
        } catch (SearchException e) {
            logger.error("Unable to get issues" + e.getMessage());
        }
        //TODO rozbić tego potwora. Gdzieś tu leci indexOutOfBounds 5 size: 5 ale boję się ruszać bo nie rozumiem
        for (Issue is : issues) {

            items = changeHistoryManager.getChangeItemsForField(is, "status");


            if (items.isEmpty()) {
                for (int i = 0; i < statuses.size(); i++) {
                    if (statuses.get(i).contains(is.getStatusObject().getName())) {

                        if (is.getCreated().after(dateBegin)) {
                            timePeriod = RegularTimePeriod.createInstance(timePeriodClass, is.getCreated(), timeZone);
                        } else {
                            timePeriod = RegularTimePeriod.createInstance(timePeriodClass, dateBegin, timeZone);
                        }
                        MutableInt num = chartPeriods.get(i).get(timePeriod);
                        if (num == null) {
                            num = new MutableInt(0);
                            chartPeriods.get(i).put(timePeriod, num);
                        }
                        num.increment();
                    }
                }
            } else {
                for (int i = 0; i < statuses.size(); i++) {
                    int index = items.size() - 1;
                    while (index >= 0 && items.get(index).getCreated().after(dateBegin)) {
                        if (statuses.get(i).contains(items.get(index).getToString())) {
                            timePeriod = RegularTimePeriod.createInstance(timePeriodClass, items.get(index).getCreated(), timeZone);
                            MutableInt num = chartPeriods.get(i).get(timePeriod);
                            if (num == null) {
                                num = new MutableInt(0);
                                chartPeriods.get(i).put(timePeriod, num);
                            }
                            num.increment();
                        }
                        index--;
                    }

                    if (index < 0) {
                        if (statuses.get(i).contains(items.get(0).getFromString())) {
                            if (is.getCreated().after(dateBegin)) {
                                timePeriod = RegularTimePeriod.createInstance(timePeriodClass, is.getCreated(), timeZone);
                            } else {
                                timePeriod = RegularTimePeriod.createInstance(timePeriodClass, dateBegin, timeZone);
                            }
                            MutableInt num = chartPeriods.get(i).get(timePeriod);
                            if (num == null) {
                                num = new MutableInt(0);
                                chartPeriods.get(i).put(timePeriod, num);
                            }
                            num.increment();
                        }
                    } else {
                        if (dateBegin.before(currentDate) && statuses.get(i).contains(items.get(index + 1).getFromString())) {
                            timePeriod = RegularTimePeriod.createInstance(timePeriodClass, dateBegin, timeZone);
                            MutableInt num = chartPeriods.get(i).get(timePeriod);
                            if (num == null) {
                                num = new MutableInt(0);
                                chartPeriods.get(i).put(timePeriod, num);
                            }
                            num.increment();
                        }
                    }

                }
            }

        }

        List<Map<RegularTimePeriod, Integer>> lists = new ArrayList<>(chartPeriods.size());
        for (int i = 0; i < statuses.size(); i++) {
            lists.add(new HashMap<RegularTimePeriod, Integer>());
        }

        Integer temp;

        for (int i = 0; i < chartPeriods.size(); i++) {
            temp = 0;
            for (Map.Entry<RegularTimePeriod, MutableInt> entry : chartPeriods.get(i).entrySet()) {
                lists.get(i).put(entry.getKey(), entry.getValue().intValue());
            }
            timePeriod = RegularTimePeriod.createInstance(timePeriodClass, dateBegin, timeZone);


            while (timePeriod.getStart().before(currentDate)) {
                if (lists.get(i).get(timePeriod) != null) {
                    temp = lists.get(i).get(timePeriod);
                } else {
                    lists.get(i).put(timePeriod, temp);
                }

                timePeriod = timePeriod.next();
            }
        }

        return lists;
    }

    private List<ValueMarker> getVersionMarkers(String projectName, Date beginDate, ChartFactory.PeriodName periodName, ChartFactory.VersionLabel versionLabel) {
        final Set<Version> versions = new HashSet<Version>();


        Long projectID = projectManager.getProjectObj(Long.parseLong(projectName.replace("project-", ""))).getId();

        versions.addAll(versionManager.getVersionsUnarchived(projectID));

        final Class periodClass = ChartUtil.getTimePeriodClass(periodName);
        final List<ValueMarker> markers = new ArrayList<>();
        for (Version version : versions) {
            if (version.getReleaseDate() != null && beginDate.before(version.getReleaseDate())) {
                RegularTimePeriod timePeriod = RegularTimePeriod.createInstance(periodClass, version.getReleaseDate(), timeZoneManager.getLoggedInUserTimeZone());
                ValueMarker vMarker = new ValueMarker(timePeriod.getFirstMillisecond());


                vMarker.setPaint(Color.GRAY);
                vMarker.setStroke(new BasicStroke(1.2f));
                vMarker.setLabelPaint(Color.GRAY);
                vMarker.setLabel(version.getName());

                markers.add(vMarker);
            }
        }
        return markers;
    }

    @ServiceReference
    public void setVersionManager(VersionManager versionManager) {
        this.versionManager = versionManager;
    }

    @ServiceReference
    public void setSearchService(SearchService searchService) {
        this.searchService = searchService;
    }

    @ServiceReference
    public void setTimeZoneManager(TimeZoneManager timeZoneManager) {
        this.timeZoneManager = timeZoneManager;
    }

    @ServiceReference
    public void setChangeHistoryManager(ChangeHistoryManager changeHistoryManager) {
        this.changeHistoryManager = changeHistoryManager;
    }

    @ServiceReference
    public void setProjectManager(ProjectManager projectManager) {
        this.projectManager = projectManager;
    }
}

