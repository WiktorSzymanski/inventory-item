#!/bin/sh
k6 run /scripts/reserve-load-test.js
wget -q -O "/reports/report_$(date +%Y%m%d_%H%M%S).pdf" \
  "http://grafana-reporter:8686/api/v5/report/the-dashboard?from=now-${DURATION}&to=now"
