# Travel Portal

## Overview

Travel Portal is a JavaFX desktop application for business-focused trip planning, booking coordination, city research, and expense reporting in a single workspace.

The app is designed around a real travel operations flow:

1. Build a multi-city route.
2. Compare estimated transport options per leg.
3. Open Booking.com and city research links from the app.
4. Sync the exact booked vendor, rate, fees, and confirmation back into the cost table.
5. Capture meeting goals, stakeholder notes, and logistics by city.
6. Produce drill-down reports for finance, reimbursement, and travel review.

The current design intentionally favors reliability and operational usefulness over fragile automation. External websites such as Booking.com are opened in the browser, while the app remains the internal system of record for booked totals, reporting, and business-trip context.

## Main Features

- Multi-city route planning with add, move, remove, and recalculate actions.
- Interactive route map rendered in JavaFX with Leaflet.
- Cost table that tracks both estimated cost and synced booked total.
- Booking Sync workspace for vendor, base rate, taxes/fees, reference, and booking notes.
- Booking Hub view with direct Booking.com launch and local hotel suggestions.
- City Guide view with restaurants, attractions, and external search links.
- Travel Brief panel with weather, air-quality, and city-context summaries from accessible public sources.
- Business Traveler Workspace for meeting goals, stakeholder plans, logistics notes, and city-specific agendas.
- Reporting area with variance visibility, filterable report modes, drilldown details, browser-print output, and export-friendly summaries.

## UX Design Narrative

The application uses a three-zone layout to reduce context switching:

- Left pane: planning and business context
- Center/right top: route map and city research
- Center/right bottom: booking, cost control, and reporting

The UX is designed around quick handoff between planning and execution:

- The route list drives the entire workspace, so selecting or reordering cities updates map, guide, and business-planning context.
- The Booking Hub and City Guide sit directly under the map because business travelers often validate accommodation, local context, and logistics while checking route shape.
- The Booking Sync form is placed immediately below the cost table so booked values can be entered without leaving the financial context.
- Reports stay visible beside the booking workflow so the user can see how each booking affects spend, receipt readiness, and variance from estimates in real time.

This design improves speed because the user does not need to move between separate trip tools, note-taking tools, spreadsheet trackers, and reporting tools.

## Business Use Case Narrative

Travel Portal is suited to business development teams, consultants, project leads, operations coordinators, and finance-conscious travelers who need more than route planning alone.

Example workflow:

- A traveler plans a Washington, DC -> Baltimore -> Philadelphia -> New York trip.
- The app compares driving, rail, and airfare estimates for each leg.
- The traveler opens Booking.com from the Booking Hub for the chosen destination and completes the actual reservation externally.
- The booked property, exact base cost, fees, and confirmation number are synced back into the selected travel leg.
- Meeting goals, client stakeholders, venue notes, and local logistics are captured by city inside the business workspace.
- Finance or management can later review the report view to see booked totals, estimate variance, pending receipts, and print-ready travel summaries.

## Cost Savings and Time Savings

### Cost Savings

- Estimated-versus-booked variance is visible in the main table, so overspend is identified immediately instead of after reimbursement.
- Exact booked totals replace rough estimates once the reservation is made, reducing reconciliation errors.
- Filtered reporting highlights booked travel and pending receipts, which helps reduce missed documentation and reimbursement leakage.
- Consolidated booking notes improve compliance for client-billable and internal travel review.

### Time Savings

- Route planning, city research, booking follow-through, and reporting happen in one interface.
- Business travelers do not need to maintain a separate spreadsheet as the authoritative booking ledger.
- Report drilldown reduces the time needed to explain receipts, vendors, and trip context to finance or leadership.
- Per-city planning notes eliminate duplicate preparation across email, note apps, and travel trackers.

## Consolidated Information Processing

The application acts as a single operating surface for:

- route sequencing
- travel cost comparison
- booked travel capture
- city guide research
- local weather and air-quality context
- business agenda planning
- stakeholder and logistics notes
- receipt and expense-report preparation

That consolidation matters because business travel decisions usually combine scheduling, spending, vendor details, local context, and reporting requirements. Travel Portal keeps those layers aligned instead of scattering them across multiple tools.

## Reporting Features

The redesigned reports section is focused on business-travel review rather than generic spend charts.

Included capabilities:

- Drilldown from chart/list view into trip-level detail.
- Filter modes for `Receipts`, `Booked Travel`, `Pending Receipts`, and `All Legs`.
- Summary line for booked total, receipt count, and pending receipt count.
- Browser-based printable report output.
- Export-friendly HTML summary suitable for PDF printing or management review.
- Visibility into booked-vs-estimate variance for each trip leg.

## Developer Narrative

The app is implemented as a single JavaFX application
Key implementation characteristics:

- Split-pane layout keeps heavy travel workflows visible without opening extra windows.
- Background threads fetch route, weather, air-quality, attraction, restaurant, and hotel data.
- UI updates are marshaled back onto the JavaFX thread with `Platform.runLater`.
- Public-source data is cached in memory to reduce repeated network calls and keep UI interactions responsive.
- External websites are opened in the browser where needed, while the application preserves the authoritative state for bookings and reports.

Primary integrations and sources:

- Nominatim for geocoding
- OpenStreetMap tiles and Leaflet for map rendering
- OSRM for route metrics
- Wikipedia for city and attraction summaries
- Open-Meteo for weather and air-quality signals
- Overpass/OpenStreetMap data for restaurant and hotel suggestions

## Screenshots


1. Route Map + Booking Hub


2. Cost Table + Booking Sync panel


3. Business Traveler Workspace


4. Reporting view with drilldown and filters


5. City Guide view



## Running the App

The project is a Maven-based JavaFX application. In IntelliJ, use the shared JavaFX run configuration if present, or run the `Main` class with the required JavaFX module path.



