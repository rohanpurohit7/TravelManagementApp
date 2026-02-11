Overview
Travel Portal is a JavaFX desktop application that unifies trip planning, route visualization, risk alerts, and expense reporting in one workspace.
It lets you build multi-city itineraries, see them plotted on an interactive map, enrich each leg with travel time and AI-assisted alerts, and roll everything into monthly spend reporting and business agendas. 
The app favors fast iteration and clear data flow: background workers gather route, weather, and attraction data while the UI stays responsive.

Main Features
- Multi-city route planning with add/move/remove/recalculate and live map updates.
- Interactive Leaflet map rendered in a JavaFX WebView.
- Per-leg pricing and travel time tracking in a structured table.
- AI alerts panel for concise travel-risk summaries with rate limiting and caching.
- Live signals for weather and air quality for destination cities.
- Attractions explorer that pulls summaries and imagery per city.
- Monthly spend visualization with drill-down expense report details.
- City-specific business agendas with SBA office alignment.

Developer Documentation Narrative
The app is built as a single JavaFX application centered in Main.java, composing the UI with SplitPane layouts and lists/tables for stateful data views. 
Network calls are executed on background threads and marshaled back to the JavaFX thread with Platform.runLater to keep the interface responsive. 
External data and AI responses are cached (in-memory maps) with throttling to reduce API load, and the UI layers are updated by refreshing observable lists and table bindings.
The map view is powered by Leaflet embedded in WebView, while routing and geocoding resolve coordinates and distances for each leg. Key integrations include Nominatim for geocoding, OpenStreetMap tiles for maps, OSRM for routing, Wikipedia for attractions, Open-Meteo for weather and air quality, and OpenAI Responses for AI alerts. 
Configuration values such as NOMINATIM_EMAIL and OPENAI_API_KEY are read from config.properties or environment variables.

Business Use Case Narrative
For business travel teams, the Travel Portal compresses the entire workflow into one tool: planners can assemble multi-city itineraries, confirm routes visually, and compare cost and time across travel modes. 
Risk and readiness are improved by live weather and air-quality signals, with AI summaries that help travelers make faster decisions. Finance gains monthly spend visibility and drill-down receipts to support compliance and reporting.
Meanwhile, business development teams can align meetings and local networking (including SBA office visits) city by city. The result is a single source of truth that reduces tool sprawl, improves traveler safety, and makes reporting audit-ready.

<img width="1908" height="1130" alt="image" src="https://github.com/user-attachments/assets/0caee399-3245-4b03-b985-b3f9e3d1c1d4" />
