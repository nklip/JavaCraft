(function () {
    const body = document.body;
    const apiBase = body.dataset.apiBase;
    const statusBanner = document.getElementById("status-banner");
    const recentRequestsBody = document.getElementById("recent-requests-body");
    const timelineEvents = document.getElementById("timeline-events");
    const timelineForm = document.getElementById("timeline-form");
    const timelineRequestIdInput = document.getElementById("timeline-request-id");
    const numberFormat = new Intl.NumberFormat();
    const dateTimeFormat = new Intl.DateTimeFormat(undefined, {
        year: "numeric",
        month: "short",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit"
    });

    const charts = {};
    const statusColors = {
        CREATED: "#0f766e",
        ACCEPTED: "#d97706",
        RUNNING: "#c27c2c",
        COMPLETED: "#1f5fa8",
        REJECTED: "#b44c33"
    };

    function statusClass(status) {
        return `status-pill status-pill-${String(status).toLowerCase()}`;
    }

    function updateBanner(message, isError) {
        statusBanner.textContent = message;
        statusBanner.classList.toggle("status-error", Boolean(isError));
    }

    function setMetric(elementId, value) {
        document.getElementById(elementId).textContent = numberFormat.format(value);
    }

    function setEmptyChart(chart, message) {
        chart.setOption({
            title: {
                text: message,
                left: "center",
                top: "middle",
                textStyle: {
                    color: "#58727a",
                    fontSize: 16,
                    fontWeight: "normal"
                }
            },
            xAxis: {show: false},
            yAxis: {show: false},
            series: []
        }, true);
    }

    function createCharts() {
        if (!window.echarts) {
            updateBanner("ECharts did not load. The dashboard page is available, but charts cannot render.", true);
            return false;
        }

        charts.status = echarts.init(document.getElementById("status-chart"));
        charts.budget = echarts.init(document.getElementById("budget-chart"));
        charts.eventVolume = echarts.init(document.getElementById("event-volume-chart"));
        charts.timeline = echarts.init(document.getElementById("timeline-chart"));

        window.addEventListener("resize", () => {
            Object.values(charts).forEach((chart) => chart.resize());
        });

        return true;
    }

    async function fetchJson(url) {
        const response = await fetch(url, {
            headers: {
                Accept: "application/json"
            }
        });

        if (!response.ok) {
            let message = `Request failed with status ${response.status}`;
            try {
                const bodyJson = await response.json();
                if (bodyJson && bodyJson.message) {
                    message = bodyJson.message;
                }
            } catch (error) {
                console.debug("Dashboard request did not return JSON", error);
            }
            throw new Error(message);
        }

        return response.json();
    }

    function renderSummary(summary, projectionHealth) {
        setMetric("metric-total", summary.totalRequests);
        setMetric("metric-open", summary.openRequests);
        setMetric("metric-completed", summary.completedRequests);
        setMetric("metric-rejected", summary.rejectedRequests);
        setMetric("metric-events", summary.storedEvents);
        setMetric("metric-lag", summary.pendingProjectionEvents);
        setMetric("health-last-applied", projectionHealth.lastAppliedEventStoreId);
        setMetric("health-latest-stored", projectionHealth.latestStoredEventId);
    }

    function renderStatusChart(statusDistribution) {
        if (!charts.status) {
            return;
        }
        if (!statusDistribution.length) {
            setEmptyChart(charts.status, "No projected requests yet");
            return;
        }

        charts.status.setOption({
            tooltip: {
                trigger: "item"
            },
            legend: {
                bottom: 0
            },
            series: [{
                name: "Requests",
                type: "pie",
                radius: ["42%", "70%"],
                avoidLabelOverlap: false,
                label: {
                    formatter: "{b}: {c}"
                },
                data: statusDistribution.map((point) => ({
                    name: point.status,
                    value: point.requestCount,
                    itemStyle: {
                        color: statusColors[point.status] || "#58727a"
                    }
                }))
            }]
        }, true);
    }

    function renderBudgetChart(budgets) {
        if (!charts.budget) {
            return;
        }
        if (!budgets.length) {
            setEmptyChart(charts.budget, "No budget projection rows yet");
            return;
        }

        charts.budget.setOption({
            color: ["#c27c2c", "#0f766e"],
            tooltip: {
                trigger: "axis",
                axisPointer: {type: "shadow"}
            },
            legend: {
                top: 0
            },
            grid: {
                left: 36,
                right: 24,
                bottom: 36,
                top: 44,
                containLabel: true
            },
            xAxis: {
                type: "category",
                data: budgets.map((budget) => budget.budgetCode)
            },
            yAxis: {
                type: "value",
                name: "Amount"
            },
            series: [{
                name: "Reserved",
                type: "bar",
                stack: "budget",
                emphasis: {focus: "series"},
                data: budgets.map((budget) => budget.reservedAmount)
            }, {
                name: "Remaining",
                type: "bar",
                stack: "budget",
                emphasis: {focus: "series"},
                data: budgets.map((budget) => budget.remainingBudget)
            }]
        }, true);
    }

    function renderEventVolumeChart(eventVolume) {
        if (!charts.eventVolume) {
            return;
        }
        if (!eventVolume.length) {
            setEmptyChart(charts.eventVolume, "No events stored yet");
            return;
        }

        charts.eventVolume.setOption({
            color: ["#0b5d57"],
            tooltip: {
                trigger: "axis"
            },
            grid: {
                left: 28,
                right: 24,
                bottom: 24,
                top: 18,
                containLabel: true
            },
            xAxis: {
                type: "category",
                boundaryGap: false,
                data: eventVolume.map((point) => point.day)
            },
            yAxis: {
                type: "value",
                name: "Events"
            },
            series: [{
                name: "Events",
                type: "line",
                smooth: true,
                areaStyle: {
                    color: "rgba(15, 118, 110, 0.18)"
                },
                data: eventVolume.map((point) => point.eventCount)
            }]
        }, true);
    }

    function renderRecentRequests(requests) {
        if (!requests.length) {
            recentRequestsBody.innerHTML = `
                <tr>
                    <td colspan="7" class="empty-cell">No projected requests yet.</td>
                </tr>
            `;
            return;
        }

        recentRequestsBody.innerHTML = requests.map((request) => `
            <tr>
                <td>${request.requestId}</td>
                <td>${request.title}</td>
                <td><span class="${statusClass(request.status)}">${request.status}</span></td>
                <td>${request.budgetCode}</td>
                <td>${request.priority}</td>
                <td>${dateTimeFormat.format(new Date(request.lastOccurredAt))}</td>
                <td>
                    <button type="button" class="table-button" data-request-id="${request.requestId}">
                        View
                    </button>
                </td>
            </tr>
        `).join("");
    }

    function renderTimelineChart(timeline, requestId) {
        if (!charts.timeline) {
            return;
        }
        if (!timeline.length) {
            setEmptyChart(charts.timeline, "No timeline events found");
            return;
        }

        charts.timeline.setOption({
            color: ["#b44c33"],
            tooltip: {
                trigger: "axis",
                formatter: (items) => {
                    const item = items[0];
                    const event = timeline[item.dataIndex];
                    const reason = event.reason ? `<br/>Reason: ${event.reason}` : "";
                    return `
                        <strong>${event.status}</strong><br/>
                        ${dateTimeFormat.format(new Date(event.occurredAt))}<br/>
                        Actor: ${event.actor}<br/>
                        Stream version: ${event.streamVersion}${reason}
                    `;
                }
            },
            grid: {
                left: 36,
                right: 24,
                bottom: 28,
                top: 24,
                containLabel: true
            },
            xAxis: {
                type: "category",
                name: `Request ${requestId}`,
                data: timeline.map((event) => event.occurredAt)
            },
            yAxis: {
                type: "value",
                minInterval: 1,
                name: "Stream Version"
            },
            series: [{
                name: "Timeline",
                type: "line",
                symbolSize: 14,
                lineStyle: {
                    width: 3
                },
                data: timeline.map((event) => event.streamVersion)
            }]
        }, true);
    }

    function renderTimelineEvents(timeline) {
        if (!timeline.length) {
            timelineEvents.innerHTML = `<p class="empty-timeline">No event history found.</p>`;
            return;
        }

        timelineEvents.innerHTML = timeline.map((event) => `
            <article class="timeline-event">
                <div class="timeline-meta">
                    <span class="${statusClass(event.status)}">${event.status}</span>
                    <strong>${dateTimeFormat.format(new Date(event.occurredAt))}</strong>
                </div>
                <h3>${event.title}</h3>
                <p>
                    Request ${event.requestId} · Actor ${event.actor} · Budget ${event.budgetCode} ·
                    Estimate ${event.estimate} · Stream ${event.streamVersion}
                </p>
                <p>${event.reason ? event.reason : "No rejection reason recorded for this event."}</p>
            </article>
        `).join("");
    }

    async function loadTimeline(requestId, updateInput) {
        try {
            const timeline = await fetchJson(`${apiBase}/work-requests/${requestId}/timeline`);
            if (updateInput) {
                timelineRequestIdInput.value = requestId;
            }
            renderTimelineChart(timeline, requestId);
            renderTimelineEvents(timeline);
            updateBanner(`Timeline for work request ${requestId} loaded.`, false);
        } catch (error) {
            renderTimelineEvents([]);
            if (charts.timeline) {
                setEmptyChart(charts.timeline, "Timeline could not be loaded");
            }
            updateBanner(error.message, true);
        }
    }

    async function loadOverview() {
        try {
            const overview = await fetchJson(`${apiBase}/overview`);
            renderSummary(overview.summary, overview.projectionHealth);
            renderStatusChart(overview.statusDistribution);
            renderBudgetChart(overview.budgets);
            renderEventVolumeChart(overview.eventVolume);
            renderRecentRequests(overview.recentRequests);

            if (overview.recentRequests.length > 0) {
                await loadTimeline(overview.recentRequests[0].requestId, true);
            } else if (charts.timeline) {
                setEmptyChart(charts.timeline, "Select a request to inspect its event history");
            }

            updateBanner("Dashboard synchronized with the current EWRS read side.", false);
        } catch (error) {
            updateBanner(error.message, true);
        }
    }

    recentRequestsBody.addEventListener("click", async (event) => {
        const button = event.target.closest("[data-request-id]");
        if (!button) {
            return;
        }
        await loadTimeline(button.dataset.requestId, true);
    });

    timelineForm.addEventListener("submit", async (event) => {
        event.preventDefault();
        const requestId = timelineRequestIdInput.value.trim();
        if (!requestId) {
            updateBanner("Enter a request id to load a timeline.", true);
            return;
        }
        await loadTimeline(requestId, false);
    });

    createCharts();
    loadOverview();
})();
