import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ListCell;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.SplitPane;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.StackedBarChart;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.Node;
import javafx.scene.shape.Rectangle;
import javafx.collections.ListChangeListener;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.geometry.Pos;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.scene.text.Text;

public class Main extends Application {
  private static final long AI_THROTTLE_MS = 1800;
  private static final long AI_RATE_LIMIT_COOLDOWN_MS = 30000;
  private static final Map<String, String> AI_SUGGESTION_CACHE = new LinkedHashMap<>();
  private static final Map<String, String> AI_QA_CACHE = new LinkedHashMap<>();
  private static long lastAiRequestAt = 0;
  private static long aiRateLimitedUntil = 0;
  private static final long GEOCODE_THROTTLE_MS = 1100;
  private static final Map<String, double[]> GEOCODE_CACHE = new LinkedHashMap<>();
  private static long lastGeocodeAt = 0;
  private static final Map<String, double[]> FALLBACK_COORDS = buildFallbackCoords();
  private static final Map<String, DrivingMetrics> OSRM_CACHE = new LinkedHashMap<>();
  private static final Map<String, List<Attraction>> ATTRACTIONS_CACHE = new LinkedHashMap<>();
  private static final Map<String, Image> MODE_IMAGE_CACHE = new LinkedHashMap<>();
  private static final Map<String, ObservableList<String>> AGENDA_ITEMS = new LinkedHashMap<>();
  private static final Map<String, String> SBA_SELECTIONS = new LinkedHashMap<>();
  private static final Map<String, List<String>> SBA_OFFICES = buildSbaOffices();
  private static final Map<String, LiveAlertData> LIVE_ALERT_CACHE = new LinkedHashMap<>();
  private static final long LIVE_ALERT_TTL_MS = 10 * 60 * 1000;

  public static void main(String[] args) {
    System.setProperty("prism.order", "sw");
    launch(args);
  }

  @Override
  public void start(Stage stage) {
    LinkedList<String> addressBook = setAddress("The World Bank, Washington DC", "20433");
    String address = getAddress(addressBook);
    List<String> routeCities = List.of("Washington, DC", "Baltimore, MD", "Philadelphia, PA", "New York, NY");

    stage.setTitle("Travel Portal- Plan, Expensify, Report");
    WebView webView = new WebView();
    webView.getEngine().setJavaScriptEnabled(true);

    Label status = new Label("Loading map...");
    status.setTextFill(Color.BLACK);
    status.setStyle("-fx-background-color: rgba(255,255,255,0.9);"
        + "-fx-padding: 6 10;"
        + "-fx-border-color: #cccccc;"
        + "-fx-border-radius: 4;"
        + "-fx-background-radius: 4;");
    StackPane mapContainer = new StackPane(webView, status);
    mapContainer.setStyle("-fx-background-color: white;");

    ListView<String> routeList = new ListView<>();
    routeList.getItems().addAll(routeCities);
    routeList.getSelectionModel().selectFirst();
    VBox.setVgrow(routeList, Priority.ALWAYS);

    TextField routeSearch = new TextField();
    routeSearch.setPromptText("Add a city or location");
    routeSearch.setPrefWidth(280);
    Button addStop = new Button("Add Stop");

    Button moveUp = new Button("Move Up");
    Button moveDown = new Button("Move Down");
    Button removeStop = new Button("Remove");
    Button recalc = new Button("Recalculate");

    HBox routeInput = new HBox(8, routeSearch, addStop);
    HBox routeControls = new HBox(8, moveUp, moveDown, removeStop, recalc);

    VBox routeBox = new VBox(6, new Label("Route order (top to bottom):"), routeInput, routeList, routeControls);

    WebView attractionsView = new WebView();
    attractionsView.setPrefHeight(200);
    Button backToMain = new Button("Back to main");
    backToMain.setStyle("-fx-background-color: rgba(255,255,255,0.9);"
        + "-fx-border-color: #cccccc;"
        + "-fx-border-radius: 4;"
        + "-fx-background-radius: 4;");
    StackPane attractionsPane = new StackPane(attractionsView, backToMain);
    StackPane.setAlignment(backToMain, Pos.TOP_RIGHT);
    StackPane.setMargin(backToMain, new javafx.geometry.Insets(6, 8, 0, 0));

    Label travelLabel = new Label("Travel date/time:");
    DatePicker travelDatePicker = new DatePicker();
    ComboBox<String> travelTimePicker = new ComboBox<>();
    travelTimePicker.getItems().addAll(buildTimeOptions());
    travelTimePicker.setPrefWidth(110);
    Button setTravelTime = new Button("Set");
    Button clearTravelTime = new Button("Clear");
    HBox travelControls = new HBox(8, travelLabel, travelDatePicker, travelTimePicker, setTravelTime, clearTravelTime);
    travelControls.setAlignment(Pos.CENTER_LEFT);

    TableView<RouteLegRow> priceTable = buildPriceTable();
    TextField aiQuestion = new TextField();
    aiQuestion.setPromptText("Ask AI about selected leg alerts");
    aiQuestion.setPrefWidth(320);
    Button askAi = new Button("Ask AI");
    Label aiContext = new Label("Selected leg: None");
    TextArea aiResponse = new TextArea();
    aiResponse.setEditable(false);
    aiResponse.setWrapText(true);
    aiResponse.setPrefRowCount(3);
    HBox aiBar = new HBox(8, aiQuestion, askAi);
    VBox aiPane = new VBox(6, new Label("AI Alerts"), aiContext, aiBar, aiResponse);
    VBox pricePane = new VBox(8, travelControls, priceTable, aiPane);
    VBox.setVgrow(priceTable, Priority.ALWAYS);
    priceTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);

    CategoryAxis spendAxis = new CategoryAxis();
    NumberAxis spendValues = new NumberAxis();
    spendValues.setLabel("Spend ($)");
    StackedBarChart<String, Number> spendChart = new StackedBarChart<>(spendAxis, spendValues);
    spendChart.setTitle("Monthly Travel Spend");
    spendChart.setLegendVisible(false);
    spendChart.setAnimated(false);
    spendChart.setPrefHeight(240);
    spendAxis.setCategories(FXCollections.observableArrayList(buildMonthLabels()));

    Button reportButton = new Button();
    reportButton.setGraphic(buildReportIcon());
    reportButton.setTooltip(new Tooltip("Toggle report details"));
    Label reportHint = new Label("Click a bar segment for details.");
    HBox reportHeader = new HBox(8, new Label("Expense Reports"), reportButton, reportHint);
    reportHeader.setAlignment(Pos.CENTER_LEFT);

    ListView<RouteLegRow> reportList = new ListView<>();
    reportList.setPrefHeight(140);
    VBox.setVgrow(reportList, Priority.ALWAYS);
    reportList.setCellFactory(list -> new ListCell<>() {
      @Override
      protected void updateItem(RouteLegRow item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText("");
        } else {
          setText(buildTripShortform(item));
        }
      }
    });
    TextArea reportDetails = new TextArea();
    reportDetails.setEditable(false);
    reportDetails.setWrapText(true);
    reportDetails.setPrefRowCount(4);
    VBox reportDetailsBox = new VBox(6, new Label("Report Details"), reportList, reportDetails);
    reportDetailsBox.setVisible(false);
    reportDetailsBox.setManaged(false);

    VBox reportingPane = new VBox(8, reportHeader, spendChart, reportDetailsBox);
    VBox.setVgrow(spendChart, Priority.ALWAYS);

    Label agendaTitle = new Label("Business Agenda");
    ComboBox<String> agendaCitySelect = new ComboBox<>();
    agendaCitySelect.setPromptText("Select city");
    ListView<String> agendaList = new ListView<>();
    agendaList.setPrefHeight(160);
    VBox.setVgrow(agendaList, Priority.ALWAYS);
    TextField agendaInput = new TextField();
    agendaInput.setPromptText("Add business agenda item");
    Button addAgenda = new Button("Add");
    Button removeAgenda = new Button("Remove");
    Button agendaUp = new Button("Move Up");
    Button agendaDown = new Button("Move Down");
    HBox agendaInputBar = new HBox(8, agendaInput, addAgenda, removeAgenda);
    HBox agendaOrderBar = new HBox(8, agendaUp, agendaDown);

    Label sbaTitle = new Label("SBA Office (Networking)");
    ComboBox<String> sbaOfficeSelect = new ComboBox<>();
    sbaOfficeSelect.setPromptText("Select SBA office");
    Label sbaSelectedLabel = new Label("No SBA office selected.");
    Button addSbaVisit = new Button("Add SBA Visit");
    Button alignSbaVisit = new Button("Align SBA Visit");
    HBox sbaActions = new HBox(8, addSbaVisit, alignSbaVisit);
    VBox businessPane = new VBox(8, agendaTitle, agendaCitySelect, agendaList, agendaInputBar,
        agendaOrderBar, sbaTitle, sbaOfficeSelect, sbaSelectedLabel, sbaActions);
    businessPane.setMinHeight(260);

    routeBox.setMinHeight(200);
    attractionsPane.setMinHeight(180);
    pricePane.setMinHeight(260);
    businessPane.setMinHeight(260);

    SplitPane mapSplit = new SplitPane();
    mapSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
    mapSplit.getItems().addAll(mapContainer, attractionsPane);
    mapSplit.setDividerPositions(0.7);

    SplitPane dataSplit = new SplitPane();
    dataSplit.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
    dataSplit.getItems().addAll(pricePane, reportingPane);
    dataSplit.setDividerPositions(0.6);

    SplitPane rightSplit = new SplitPane();
    rightSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
    rightSplit.getItems().addAll(mapSplit, dataSplit);
    rightSplit.setDividerPositions(0.58);

    VBox leftPane = new VBox(10, routeBox, businessPane);
    VBox.setVgrow(routeBox, Priority.ALWAYS);
    VBox.setVgrow(businessPane, Priority.ALWAYS);

    SplitPane root = new SplitPane();
    root.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
    root.getItems().addAll(leftPane, rightSplit);
    root.setDividerPositions(0.3);

    Scene scene = new Scene(root, 900, 600, Color.WHITE);
    stage.setScene(scene);
    stage.show();

    // loadMap(webView, status, address);
    loadRouteData(webView, status, priceTable, routeCities);
    loadAttractions(attractionsView, routeCities);

    moveUp.setOnAction(event -> moveSelected(routeList, -1));
    moveDown.setOnAction(event -> moveSelected(routeList, 1));
    removeStop.setOnAction(event -> removeSelected(routeList));
    recalc.setOnAction(event -> {
      List<String> ordered = new ArrayList<>(routeList.getItems());
      loadRouteData(webView, status, priceTable, ordered);
      loadAttractions(attractionsView, ordered);
    });
    askAi.setOnAction(event -> askAiForSelection(priceTable, aiQuestion, aiResponse, aiContext));
    addStop.setOnAction(event -> addRouteStop(routeSearch, routeList));
    routeSearch.setOnAction(event -> addRouteStop(routeSearch, routeList));
    backToMain.setOnAction(event -> {
      routeList.requestFocus();
      routeList.getSelectionModel().selectFirst();
    });

    Runnable spendUpdater = () -> updateMonthlySpend(priceTable, spendChart, reportList, reportDetails);
    priceTable.setUserData(spendUpdater);
    updateMonthlySpend(priceTable, spendChart, reportList, reportDetails);

    priceTable.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
      if (newRow == null) {
        travelDatePicker.setValue(null);
        travelTimePicker.setValue(null);
        updateAiPanel(null, aiQuestion, aiResponse, aiContext);
        return;
      }
      travelDatePicker.setValue(newRow.getTravelDate());
      String time = newRow.getTravelTime();
      if (time == null || time.isBlank()) {
        travelTimePicker.setValue(null);
      } else {
        travelTimePicker.setValue(time);
      }
      updateAiPanel(newRow, aiQuestion, aiResponse, aiContext);
    });

    setTravelTime.setOnAction(event -> {
      RouteLegRow row = priceTable.getSelectionModel().getSelectedItem();
      if (row == null) {
        return;
      }
      row.setTravelDate(travelDatePicker.getValue());
      String time = travelTimePicker.getValue();
      row.setTravelTime(time == null ? "" : time.trim());
      priceTable.refresh();
      triggerSpendUpdate(priceTable);
    });

    clearTravelTime.setOnAction(event -> {
      RouteLegRow row = priceTable.getSelectionModel().getSelectedItem();
      if (row == null) {
        return;
      }
      row.setTravelDate(null);
      row.setTravelTime("");
      priceTable.refresh();
      triggerSpendUpdate(priceTable);
    });

    reportButton.setOnAction(event -> {
      boolean next = !reportDetailsBox.isVisible();
      reportDetailsBox.setVisible(next);
      reportDetailsBox.setManaged(next);
    });

    reportList.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
      if (newRow == null) {
        reportDetails.setText("");
      } else {
        reportDetails.setText(buildReportDetail(newRow));
      }
    });

    refreshAgendaCities(routeList, agendaCitySelect, agendaList, sbaOfficeSelect, sbaSelectedLabel);
    agendaCitySelect.setOnAction(event -> updateAgendaForCity(agendaCitySelect, agendaList, sbaOfficeSelect, sbaSelectedLabel));
    routeList.getItems().addListener((ListChangeListener<String>) change -> {
      refreshAgendaCities(routeList, agendaCitySelect, agendaList, sbaOfficeSelect, sbaSelectedLabel);
    });

    addAgenda.setOnAction(event -> addAgendaItem(agendaInput, agendaList));
    agendaInput.setOnAction(event -> addAgendaItem(agendaInput, agendaList));
    removeAgenda.setOnAction(event -> removeAgendaItem(agendaList));
    agendaUp.setOnAction(event -> moveAgendaItem(agendaList, -1));
    agendaDown.setOnAction(event -> moveAgendaItem(agendaList, 1));

    sbaOfficeSelect.setOnAction(event -> {
      String city = agendaCitySelect.getValue();
      String office = sbaOfficeSelect.getValue();
      if (city == null || office == null || office.isBlank()) {
        return;
      }
      SBA_SELECTIONS.put(normalizePlaceKey(city), office);
      sbaSelectedLabel.setText(office);
    });
    addSbaVisit.setOnAction(event -> addSbaVisit(agendaCitySelect, agendaList, sbaOfficeSelect, false));
    alignSbaVisit.setOnAction(event -> addSbaVisit(agendaCitySelect, agendaList, sbaOfficeSelect, true));
  }

  private static LinkedList<String> setAddress(String street, String zipcode) {
    LinkedList<String> addressBook = new LinkedList<>();
    addressBook.add(street);
    addressBook.add(zipcode);
    return addressBook;
  }

  private static String getAddress(LinkedList<String> addressBook) {
    return String.join(", ", addressBook);
  }

  private static void loadMap(WebView webView, Label status, String address) {
    Thread worker = new Thread(() -> {
      double[] latLon = geocode(address);
      double lat = latLon != null ? latLon[0] : 38.9;
      double lon = latLon != null ? latLon[1] : -77.04;
      Platform.runLater(() -> loadMapHtml(webView, status, address, lat, lon));
    }, "geocode-worker");
    worker.setDaemon(true);
    worker.start();
  }

  private static void loadRouteMap(WebView webView, Label status, List<String> cities) {
    Thread worker = new Thread(() -> {
      List<RouteStop> stops = resolveRouteStops(cities);
      String html = buildRouteMapHtml(stops);
      Platform.runLater(() -> loadMapHtml(webView, status, html));
    }, "route-worker");
    worker.setDaemon(true);
    worker.start();
  }

  private static void loadRoutePrices(TableView<RouteLegRow> table, List<String> cities) {
    Thread worker = new Thread(() -> {
      List<RouteStop> stops = resolveRouteStops(cities);
      ObservableList<RouteLegRow> rows = routeTravelPriceAnalysis(stops);
      Platform.runLater(() -> {
        table.setItems(rows);
        if (!rows.isEmpty()) {
          table.getSelectionModel().selectFirst();
        }
        triggerSpendUpdate(table);
        populateLiveAlerts(table);
      });
    }, "price-worker");
    worker.setDaemon(true);
    worker.start();
  }

  private static void loadRouteData(WebView webView, Label status, TableView<RouteLegRow> table,
      List<String> cities) {
    Thread worker = new Thread(() -> {
      List<RouteStop> stops = resolveRouteStops(cities);
      String html = buildRouteMapHtml(stops);
      ObservableList<RouteLegRow> rows = routeTravelPriceAnalysis(stops);
      Platform.runLater(() -> {
        loadMapHtml(webView, status, html);
        table.setItems(rows);
        if (!rows.isEmpty()) {
          table.getSelectionModel().selectFirst();
        }
        triggerSpendUpdate(table);
        populateLiveAlerts(table);
      });
    }, "route-data-worker");
    worker.setDaemon(true);
    worker.start();
  }

  private static void loadAttractions(WebView webView, List<String> cities) {
    Thread worker = new Thread(() -> {
      List<Attraction> attractions = fetchAttractions(cities);
      String html = buildAttractionsHtml(attractions);
      Platform.runLater(() -> webView.getEngine().loadContent(html));
    }, "attractions-worker");
    worker.setDaemon(true);
    worker.start();
  }

  private static double[] geocode(String address) {
    if (address == null || address.isBlank()) {
      return null;
    }
    double[] inputCoords = parseLatLonInput(address);
    if (inputCoords != null) {
      return inputCoords;
    }
    String key = normalizePlaceKey(address);
    double[] fallbackCoords = FALLBACK_COORDS.get(key);
    if (fallbackCoords != null) {
      return fallbackCoords.clone();
    }
    synchronized (GEOCODE_CACHE) {
      double[] cached = GEOCODE_CACHE.get(key);
      if (cached != null) {
        return cached.clone();
      }
    }
    try {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(8))
          .build();
      String encoded = URLEncoder.encode(address, StandardCharsets.UTF_8);
      String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&q=" + encoded;
      String email = readConfigValue("NOMINATIM_EMAIL");
      if (email == null || email.isBlank()) {
        email = System.getenv("NOMINATIM_EMAIL");
      }
      if (email != null && !email.isBlank()) {
        url += "&email=" + URLEncoder.encode(email, StandardCharsets.UTF_8);
      }
      String userAgent = readConfigValue("NOMINATIM_USER_AGENT");
      if (userAgent == null || userAgent.isBlank()) {
        userAgent = System.getenv("NOMINATIM_USER_AGENT");
      }
      if (userAgent == null || userAgent.isBlank()) {
        userAgent = "MyProject-Geovisualization (no-email)";
      }
      for (int attempt = 0; attempt < 2; attempt++) {
        throttleGeocodeRequests();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", userAgent)
            .header("Accept-Language", "en-US")
            .timeout(Duration.ofSeconds(12))
            .GET()
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          if (attempt == 0) {
            Thread.sleep(700);
            continue;
          }
          return null;
        }
        double[] latLon = parseLatLon(response.body());
        if (latLon != null) {
          synchronized (GEOCODE_CACHE) {
            GEOCODE_CACHE.put(key, latLon);
          }
          return latLon;
        }
        if (attempt == 0) {
          Thread.sleep(500);
        }
      }
      double[] wikiCoords = geocodeViaWikipedia(address, userAgent);
      if (wikiCoords != null) {
        synchronized (GEOCODE_CACHE) {
          GEOCODE_CACHE.put(key, wikiCoords);
        }
      }
      return wikiCoords;
    } catch (Exception e) {
      return null;
    }
  }

  private static String normalizePlaceKey(String value) {
    return value.trim().toLowerCase().replaceAll("\\s+", " ");
  }

  private static double[] parseLatLonInput(String value) {
    Pattern pattern = Pattern.compile("^\\s*([+-]?[0-9.]+)\\s*,\\s*([+-]?[0-9.]+)\\s*$");
    Matcher matcher = pattern.matcher(value);
    if (!matcher.find()) {
      return null;
    }
    double lat = Double.parseDouble(matcher.group(1));
    double lon = Double.parseDouble(matcher.group(2));
    return new double[] {lat, lon};
  }

  private static Map<String, double[]> buildFallbackCoords() {
    Map<String, double[]> map = new LinkedHashMap<>();
    map.put("washington, dc", new double[] {38.9072, -77.0369});
    map.put("washington dc", new double[] {38.9072, -77.0369});
    map.put("baltimore, md", new double[] {39.2904, -76.6122});
    map.put("baltimore md", new double[] {39.2904, -76.6122});
    map.put("philadelphia, pa", new double[] {39.9526, -75.1652});
    map.put("philadelphia pa", new double[] {39.9526, -75.1652});
    map.put("new york, ny", new double[] {40.7128, -74.0060});
    map.put("new york ny", new double[] {40.7128, -74.0060});
    map.put("new york city", new double[] {40.7128, -74.0060});
    return map;
  }

  private static double[] parseLatLon(String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    Pattern latPattern = Pattern.compile("\"lat\"\\s*:\\s*\"?([0-9.+-]+)\"?");
    Pattern lonPattern = Pattern.compile("\"lon\"\\s*:\\s*\"?([0-9.+-]+)\"?");
    Matcher latMatcher = latPattern.matcher(body);
    Matcher lonMatcher = lonPattern.matcher(body);
    if (!latMatcher.find() || !lonMatcher.find()) {
      return null;
    }
    double lat = Double.parseDouble(latMatcher.group(1));
    double lon = Double.parseDouble(lonMatcher.group(1));
    return new double[] {lat, lon};
  }

  private static void loadMapHtml(WebView webView, Label status, String address, double lat, double lon) {
    String html = buildMapHtml(address, lat, lon);
    loadMapHtml(webView, status, html);
  }

  private static void loadMapHtml(WebView webView, Label status, String html) {
    webView.getEngine().loadContent(html);
    webView.getEngine().getLoadWorker().stateProperty().addListener((obs, oldVal, newVal) -> {
      if (newVal == Worker.State.SUCCEEDED) {
        status.setText("");
      } else if (newVal == Worker.State.FAILED) {
        status.setText("Failed to load map.");
      }
    });
  }

  private static String buildMapHtml(String address, double lat, double lon) {
    String escaped = address.replace("\\", "\\\\").replace("'", "\\'");
    String leafletCss = Main.class.getResource("/leaflet/leaflet.css").toExternalForm();
    String leafletJs = Main.class.getResource("/leaflet/leaflet.js").toExternalForm();
    return "<!doctype html>\n"
        + "<html>\n"
        + "<head>\n"
        + "  <meta charset='utf-8'/>\n"
        + "  <meta name='viewport' content='width=device-width, initial-scale=1.0'/>\n"
        + "  <link rel='stylesheet' href='" + leafletCss + "'/>\n"
        + "  <style>html,body,#map{height:100%;margin:0;padding:0;background:#fff;}</style>\n"
        + "</head>\n"
        + "<body>\n"
        + "  <div id='map'></div>\n"
        + "  <script src='" + leafletJs + "'></script>\n"
        + "  <script>\n"
        + "    const address = '" + escaped + "';\n"
        + "    const map = L.map('map').setView([" + lat + ", " + lon + "], 13);\n"
        + "    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {\n"
        + "      maxZoom: 19,\n"
        + "      attribution: '&copy; OpenStreetMap contributors'\n"
        + "    }).addTo(map);\n"
        + "    L.marker([" + lat + ", " + lon + "]).addTo(map).bindPopup(address).openPopup();\n"
        + "  </script>\n"
        + "</body>\n"
        + "</html>\n";
  }

  private static String buildRouteMapHtml(List<RouteStop> stops) {
    if (stops.isEmpty()) {
      return buildMapHtml("No route data", 38.9, -77.04);
    }

    StringBuilder points = new StringBuilder("[");
    for (int i = 0; i < stops.size(); i++) {
      RouteStop stop = stops.get(i);
      if (i > 0) {
        points.append(",");
      }
      points.append("[").append(stop.lat).append(",").append(stop.lon).append("]");
    }
    points.append("]");

    StringBuilder labels = new StringBuilder("[");
    for (int i = 0; i < stops.size(); i++) {
      if (i > 0) {
        labels.append(",");
      }
      String escaped = stops.get(i).city.replace("\\", "\\\\").replace("'", "\\'");
      labels.append("'").append(escaped).append("'");
    }
    labels.append("]");

    String leafletCss = Main.class.getResource("/leaflet/leaflet.css").toExternalForm();
    String leafletJs = Main.class.getResource("/leaflet/leaflet.js").toExternalForm();
    return "<!doctype html>\n"
        + "<html>\n"
        + "<head>\n"
        + "  <meta charset='utf-8'/>\n"
        + "  <meta name='viewport' content='width=device-width, initial-scale=1.0'/>\n"
        + "  <link rel='stylesheet' href='" + leafletCss + "'/>\n"
        + "  <style>html,body,#map{height:100%;margin:0;padding:0;background:#fff;}</style>\n"
        + "</head>\n"
        + "<body>\n"
        + "  <div id='map'></div>\n"
        + "  <script src='" + leafletJs + "'></script>\n"
        + "  <script>\n"
        + "    const points = " + points + ";\n"
        + "    const labels = " + labels + ";\n"
        + "    const colors = ['#e41a1c','#377eb8','#4daf4a','#984ea3','#ff7f00','#a65628'];\n"
        + "    const map = L.map('map').setView(points[0], 6);\n"
        + "    L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {\n"
        + "      maxZoom: 19,\n"
        + "      attribution: '&copy; OpenStreetMap contributors'\n"
        + "    }).addTo(map);\n"
        + "    for (let i = 0; i < points.length; i++) {\n"
        + "      L.marker(points[i]).addTo(map).bindPopup(labels[i] || ('Stop ' + (i + 1)));\n"
        + "      if (i > 0) {\n"
        + "        const seg = [points[i - 1], points[i]];\n"
        + "        L.polyline(seg, { color: colors[(i - 1) % colors.length], weight: 4 }).addTo(map);\n"
        + "      }\n"
        + "    }\n"
        + "    const bounds = L.latLngBounds(points);\n"
        + "    map.fitBounds(bounds.pad(0.2));\n"
        + "  </script>\n"
        + "</body>\n"
        + "</html>\n";
  }

  private static ObservableList<RouteLegRow> routeTravelPriceAnalysis(List<RouteStop> stops) {
    List<RouteLegRow> rows = new ArrayList<>();
    for (int i = 1; i < stops.size(); i++) {
      RouteStop from = stops.get(i - 1);
      RouteStop to = stops.get(i);
      double miles = haversineMiles(from.lat, from.lon, to.lat, to.lon);

      DrivingMetrics driving = getDrivingMetrics(from, to, miles);
      ModeMetrics train = estimateTrain(miles);
      ModeMetrics air = estimateAirfare(miles);

      String alertPlaceholder = buildAlertPlaceholder();
      rows.add(new RouteLegRow(from.city, to.city, "Driving", driving.cost, driving.hours, "", alertPlaceholder, to.lat, to.lon));
      rows.add(new RouteLegRow(from.city, to.city, "Train", train.cost, train.hours, "", alertPlaceholder, to.lat, to.lon));
      rows.add(new RouteLegRow(from.city, to.city, "Airfare", air.cost, air.hours, "", alertPlaceholder, to.lat, to.lon));
    }

    return FXCollections.observableArrayList(rows);
  }

  private static List<RouteStop> resolveRouteStops(List<String> cities) {
    List<RouteStop> stops = new ArrayList<>();
    for (String city : cities) {
      double[] latLon = geocode(city);
      if (latLon != null) {
        stops.add(new RouteStop(city, latLon[0], latLon[1]));
      }
    }
    return stops;
  }

  private static List<Attraction> fetchAttractions(List<String> cities) {
    List<Attraction> results = new ArrayList<>();
    for (String city : cities) {
      List<Attraction> cached = ATTRACTIONS_CACHE.get(city);
      if (cached != null) {
        results.addAll(cached);
        continue;
      }
      List<Attraction> cityResults = new ArrayList<>();
      List<String> titles = searchAttractionTitles(city, 3);
      for (String title : titles) {
        Attraction attraction = fetchAttractionSummary(city, title);
        if (attraction != null) {
          cityResults.add(attraction);
        }
      }
      ATTRACTIONS_CACHE.put(city, cityResults);
      results.addAll(cityResults);
    }
    return results;
  }

  private static List<String> searchAttractionTitles(String city, int limit) {
    List<String> titles = new ArrayList<>();
    try {
      String query = URLEncoder.encode(city + " attractions", StandardCharsets.UTF_8);
      String url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch="
          + query + "&format=json&utf8=1&srlimit=" + limit;
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", "MyProject-Geovisualization")
          .GET()
          .build();
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return titles;
      }
      Matcher matcher = Pattern.compile("\"title\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL).matcher(response.body());
      while (matcher.find() && titles.size() < limit) {
        String title = jsonUnescape(matcher.group(1));
        titles.add(title);
      }
    } catch (Exception e) {
      return titles;
    }
    return titles;
  }

  private static String searchWikipediaTitle(String query, String userAgent) {
    try {
      String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
      String url = "https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch="
          + encoded + "&format=json&utf8=1&srlimit=1";
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", userAgent)
          .GET()
          .build();
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return null;
      }
      Matcher matcher = Pattern.compile("\"title\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL).matcher(response.body());
      if (matcher.find()) {
        return jsonUnescape(matcher.group(1));
      }
      return null;
    } catch (Exception e) {
      return null;
    }
  }

  private static String fetchWikipediaSummary(String title, String userAgent) {
    try {
      String encodedTitle = encodePathSegment(title);
      String url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + encodedTitle;
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", userAgent)
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build();
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(6))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return null;
      }
      return matchField(response.body(), "\"extract\"\\s*:\\s*\"(.*?)\"");
    } catch (Exception e) {
      return null;
    }
  }

  private static double[] geocodeViaWikipedia(String query, String userAgent) {
    try {
      String title = searchWikipediaTitle(query, userAgent);
      if (title == null || title.isBlank()) {
        return null;
      }
      String encodedTitle = encodePathSegment(title);
      String url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + encodedTitle;
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", userAgent)
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build();
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(6))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return null;
      }
      return parseLatLon(response.body());
    } catch (Exception e) {
      return null;
    }
  }

  private static Attraction fetchAttractionSummary(String city, String title) {
    try {
      String encodedTitle = encodePathSegment(title);
      String url = "https://en.wikipedia.org/api/rest_v1/page/summary/" + encodedTitle;
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", "MyProject-Geovisualization")
          .GET()
          .build();
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return null;
      }
      String body = response.body();
      String image = matchField(body, "\"source\"\\s*:\\s*\"(.*?)\"");
      String link = matchField(body, "\"page\"\\s*:\\s*\"(.*?)\"");
      if (link == null || link.isBlank()) {
        return null;
      }
      return new Attraction(city, title, image, link);
    } catch (Exception e) {
      return null;
    }
  }

  private static String buildAttractionsHtml(List<Attraction> attractions) {
    Map<String, List<Attraction>> byCity = new LinkedHashMap<>();
    for (Attraction attraction : attractions) {
      byCity.computeIfAbsent(attraction.city, key -> new ArrayList<>()).add(attraction);
    }
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset='utf-8'/>")
        .append("<style>")
        .append("body{font-family:Arial,sans-serif;margin:0;background:#f7f7f7;}")
        .append(".section{padding:10px 12px 4px 12px;}")
        .append(".title{font-size:14px;font-weight:bold;margin:0 0 6px 0;}")
        .append(".carousel{display:flex;gap:10px;overflow-x:auto;padding-bottom:10px;scroll-snap-type:x mandatory;}")
        .append(".card{flex:0 0 220px;border-radius:8px;background:#fff;box-shadow:0 1px 4px rgba(0,0,0,0.12);")
        .append("scroll-snap-align:start;text-decoration:none;color:#222;}")
        .append(".img{height:120px;border-top-left-radius:8px;border-top-right-radius:8px;background:#ddd;")
        .append("display:flex;align-items:center;justify-content:center;color:#666;font-size:12px;}")
        .append(".img img{width:100%;height:100%;object-fit:cover;border-top-left-radius:8px;border-top-right-radius:8px;}")
        .append(".name{padding:8px 10px;font-size:12px;font-weight:bold;}")
        .append(".link{padding:0 10px 10px 10px;font-size:11px;color:#2b6cb0;}")
        .append("</style></head><body>");

    if (byCity.isEmpty()) {
      html.append("<div class='section'><div class='title'>Attractions</div>")
          .append("<div>No attractions found.</div></div>");
    }

    for (Map.Entry<String, List<Attraction>> entry : byCity.entrySet()) {
      html.append("<div class='section'>")
          .append("<div class='title'>").append(escapeHtml(entry.getKey())).append("</div>")
          .append("<div class='carousel'>");
      for (Attraction attraction : entry.getValue()) {
        String image = attraction.imageUrl != null ? attraction.imageUrl : "";
        html.append("<a class='card' href='").append(escapeHtml(attraction.link)).append("'>")
            .append("<div class='img'>");
        if (!image.isBlank()) {
          html.append("<img src='").append(escapeHtml(image)).append("'/>");
        } else {
          html.append("No Image");
        }
        html.append("</div>")
            .append("<div class='name'>").append(escapeHtml(attraction.title)).append("</div>")
            .append("<div class='link'>Wikipedia</div>")
            .append("</a>");
      }
      html.append("</div></div>");
    }
    html.append("</body></html>");
    return html.toString();
  }

  private static String matchField(String body, String regex) {
    Matcher matcher = Pattern.compile(regex, Pattern.DOTALL).matcher(body);
    if (!matcher.find()) {
      return null;
    }
    return jsonUnescape(matcher.group(1));
  }

  private static String encodePathSegment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static DrivingMetrics getDrivingMetrics(RouteStop from, RouteStop to, double fallbackMiles) {
    double gasPricePerGallon = 3.75;
    double mpg = 28.0;
    double drivingSpeedMph = 58.0;
    double miles = fallbackMiles;
    double hours = fallbackMiles / drivingSpeedMph;

    DrivingMetrics osrm = queryOsrm(from, to);
    if (osrm != null) {
      miles = osrm.miles;
      hours = osrm.hours;
    }

    double cost = (miles / mpg) * gasPricePerGallon;
    return new DrivingMetrics(cost, hours, miles);
  }

  private static ModeMetrics estimateTrain(double miles) {
    double trainCostPerMile = 0.35;
    double trainSpeedMph = 70.0;
    double cost = miles * trainCostPerMile;
    double hours = miles / trainSpeedMph;
    return new ModeMetrics(cost, hours);
  }

  private static ModeMetrics estimateAirfare(double miles) {
    double airfareBase = 55.0;
    double airfarePerMile = 0.18;
    double airSpeedMph = 430.0;
    double airOverheadHours = 1.5;
    double cost = airfareBase + (miles * airfarePerMile);
    double hours = (miles / airSpeedMph) + airOverheadHours;
    return new ModeMetrics(cost, hours);
  }

  private static DrivingMetrics queryOsrm(RouteStop from, RouteStop to) {
    String key = from.lat + "," + from.lon + "->" + to.lat + "," + to.lon;
    DrivingMetrics cached = OSRM_CACHE.get(key);
    if (cached != null) {
      return cached;
    }
    try {
      String url = "https://router.project-osrm.org/route/v1/driving/"
          + from.lon + "," + from.lat + ";" + to.lon + "," + to.lat
          + "?overview=false";
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", "MyProject-Geovisualization")
          .GET()
          .build();
      HttpClient client = HttpClient.newHttpClient();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return null;
      }
      DrivingMetrics metrics = parseOsrm(response.body());
      if (metrics != null) {
        OSRM_CACHE.put(key, metrics);
      }
      return metrics;
    } catch (Exception e) {
      return null;
    }
  }

  private static DrivingMetrics parseOsrm(String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    Pattern distancePattern = Pattern.compile("\"distance\"\\s*:\\s*([0-9.]+)");
    Pattern durationPattern = Pattern.compile("\"duration\"\\s*:\\s*([0-9.]+)");
    Matcher distanceMatcher = distancePattern.matcher(body);
    Matcher durationMatcher = durationPattern.matcher(body);
    if (!distanceMatcher.find() || !durationMatcher.find()) {
      return null;
    }
    double meters = Double.parseDouble(distanceMatcher.group(1));
    double seconds = Double.parseDouble(durationMatcher.group(1));
    double miles = meters * 0.000621371;
    double hours = seconds / 3600.0;
    return new DrivingMetrics(0.0, hours, miles);
  }

  private static double haversineMiles(double lat1, double lon1, double lat2, double lon2) {
    double r = 3958.7613;
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
        * Math.sin(dLon / 2) * Math.sin(dLon / 2);
    double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    return r * c;
  }

  private static TableView<RouteLegRow> buildPriceTable() {
    TableView<RouteLegRow> table = new TableView<>();
    table.setPrefHeight(220);

    TableColumn<RouteLegRow, String> fromCol = new TableColumn<>("From");
    fromCol.setCellValueFactory(new PropertyValueFactory<>("from"));
    TableColumn<RouteLegRow, String> toCol = new TableColumn<>("To");
    toCol.setCellValueFactory(new PropertyValueFactory<>("to"));
    TableColumn<RouteLegRow, String> modeCol = new TableColumn<>("Mode");
    modeCol.setCellValueFactory(new PropertyValueFactory<>("mode"));
    modeCol.setCellFactory(col -> new TableCell<>() {
      private final ImageView imageView = new ImageView();
      private final Label label = new Label();
      private final HBox box = new HBox(6, imageView, label);

      {
        imageView.setFitHeight(28);
        imageView.setFitWidth(46);
        imageView.setPreserveRatio(true);
        box.setAlignment(Pos.CENTER_LEFT);
      }

      @Override
      protected void updateItem(String item, boolean empty) {
        super.updateItem(item, empty);
        if (empty || item == null) {
          setText("");
          setGraphic(null);
          return;
        }
        label.setText(item);
        imageView.setImage(loadModeImage(item));
        setText("");
        setGraphic(box);
      }
    });
    modeCol.setComparator((a, b) -> Integer.compare(modeRank(a), modeRank(b)));
    TableColumn<RouteLegRow, Double> costCol = new TableColumn<>("Cost ($)");
    costCol.setCellValueFactory(new PropertyValueFactory<>("cost"));
    costCol.setCellFactory(col -> new TableCell<>() {
      @Override
      protected void updateItem(Double item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? "" : formatCurrency(item));
      }
    });
    TableColumn<RouteLegRow, Double> timeCol = new TableColumn<>("Time (hrs)");
    timeCol.setCellValueFactory(new PropertyValueFactory<>("hours"));
    timeCol.setCellFactory(col -> new TableCell<>() {
      @Override
      protected void updateItem(Double item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? "" : formatDuration(item));
      }
    });

    TableColumn<RouteLegRow, String> travelDateCol = new TableColumn<>("Travel Date");
    travelDateCol.setCellValueFactory(new PropertyValueFactory<>("travelDateDisplay"));
    travelDateCol.setPrefWidth(110);

    TableColumn<RouteLegRow, String> travelTimeCol = new TableColumn<>("Travel Time");
    travelTimeCol.setCellValueFactory(new PropertyValueFactory<>("travelTime"));
    travelTimeCol.setPrefWidth(90);

    TableColumn<RouteLegRow, Void> bookActionCol = new TableColumn<>("Book");
    bookActionCol.setCellFactory(col -> new TableCell<>() {
      private final Button bookButton = new Button("Book");

      {
        bookButton.setOnAction(event -> {
          RouteLegRow row = getTableView().getItems().get(getIndex());
          if (row == null || row.isBooked()) {
            return;
          }
          row.book(generateBookingReference(row));
          getTableView().refresh();
        });
      }

      @Override
      protected void updateItem(Void item, boolean empty) {
        super.updateItem(item, empty);
        if (empty) {
          setGraphic(null);
          return;
        }
        RouteLegRow row = getTableView().getItems().get(getIndex());
        boolean booked = row != null && row.isBooked();
        bookButton.setText(booked ? "Booked" : "Book");
        bookButton.setDisable(booked);
        setGraphic(bookButton);
      }
    });

    TableColumn<RouteLegRow, String> bookingStatusCol = new TableColumn<>("Booking Status");
    bookingStatusCol.setCellValueFactory(new PropertyValueFactory<>("bookingStatus"));
    bookingStatusCol.setPrefWidth(120);

    TableColumn<RouteLegRow, String> bookingRefCol = new TableColumn<>("Booking Ref");
    bookingRefCol.setCellValueFactory(new PropertyValueFactory<>("bookingReference"));
    bookingRefCol.setPrefWidth(120);

    TableColumn<RouteLegRow, Void> receiptActionCol = new TableColumn<>("Receipt");
    receiptActionCol.setCellFactory(col -> new TableCell<>() {
      private final Button receiptButton = new Button("Generate");

      {
        receiptButton.setOnAction(event -> {
          RouteLegRow row = getTableView().getItems().get(getIndex());
          if (row == null || !row.isBooked() || row.hasReceipt()) {
            return;
          }
          String receiptId = generateReceiptId(row);
          row.issueReceipt(receiptId, buildExpenseSummary(row, receiptId));
          triggerSpendUpdate(getTableView());
          getTableView().refresh();
        });
      }

      @Override
      protected void updateItem(Void item, boolean empty) {
        super.updateItem(item, empty);
        if (empty) {
          setGraphic(null);
          return;
        }
        RouteLegRow row = getTableView().getItems().get(getIndex());
        boolean booked = row != null && row.isBooked();
        boolean hasReceipt = row != null && row.hasReceipt();
        receiptButton.setText(hasReceipt ? "Generated" : "Generate");
        receiptButton.setDisable(!booked || hasReceipt);
        setGraphic(receiptButton);
      }
    });

    TableColumn<RouteLegRow, String> receiptIdCol = new TableColumn<>("Receipt ID");
    receiptIdCol.setCellValueFactory(new PropertyValueFactory<>("receiptId"));
    receiptIdCol.setPrefWidth(140);

    TableColumn<RouteLegRow, String> expenseSummaryCol = new TableColumn<>("Expense Summary");
    expenseSummaryCol.setCellValueFactory(new PropertyValueFactory<>("expenseSummary"));
    expenseSummaryCol.setPrefWidth(260);
    expenseSummaryCol.setCellFactory(col -> new WrappingTableCell());

    TableColumn<RouteLegRow, String> aiCol = new TableColumn<>("AI Suggestions");
    aiCol.setCellValueFactory(new PropertyValueFactory<>("aiSuggestion"));
    aiCol.setPrefWidth(360);
    aiCol.setCellFactory(col -> new WrappingTableCell());

    table.getColumns().add(fromCol);
    table.getColumns().add(toCol);
    table.getColumns().add(modeCol);
    table.getColumns().add(costCol);
    table.getColumns().add(timeCol);
    table.getColumns().add(travelDateCol);
    table.getColumns().add(travelTimeCol);
    table.getColumns().add(bookActionCol);
    table.getColumns().add(bookingStatusCol);
    table.getColumns().add(bookingRefCol);
    table.getColumns().add(receiptActionCol);
    table.getColumns().add(receiptIdCol);
    table.getColumns().add(expenseSummaryCol);
    table.getColumns().add(aiCol);

    return table;
  }


  private static final class RouteStop {
    private final String city;
    private final double lat;
    private final double lon;

    private RouteStop(String city, double lat, double lon) {
      this.city = city;
      this.lat = lat;
      this.lon = lon;
    }
  }

  private static final class DrivingMetrics {
    private final double cost;
    private final double hours;
    private final double miles;

    private DrivingMetrics(double cost, double hours, double miles) {
      this.cost = cost;
      this.hours = hours;
      this.miles = miles;
    }
  }

  private static final class ModeMetrics {
    private final double cost;
    private final double hours;

    private ModeMetrics(double cost, double hours) {
      this.cost = cost;
      this.hours = hours;
    }
  }

  private static final class Attraction {
    private final String city;
    private final String title;
    private final String imageUrl;
    private final String link;

    private Attraction(String city, String title, String imageUrl, String link) {
      this.city = city;
      this.title = title;
      this.imageUrl = imageUrl;
      this.link = link;
    }
  }

  private static final class LiveAlertData {
    private final String weather;
    private final String aqi;
    private final long fetchedAt;

    private LiveAlertData(String weather, String aqi, long fetchedAt) {
      this.weather = weather;
      this.aqi = aqi;
      this.fetchedAt = fetchedAt;
    }
  }

  public static final class RouteLegRow {
    private final String from;
    private final String to;
    private final String mode;
    private final double cost;
    private final double hours;
    private final double toLat;
    private final double toLon;
    private String question;
    private String aiSuggestion;
    private String bookingStatus;
    private String bookingReference;
    private String receiptId;
    private String expenseSummary;
    private LocalDate travelDate;
    private String travelTime;
    private LocalDate receiptDate;

    public RouteLegRow(String from, String to, String mode, double cost, double hours, String question,
        String aiSuggestion, double toLat, double toLon) {
      this.from = from;
      this.to = to;
      this.mode = mode;
      this.cost = cost;
      this.hours = hours;
      this.toLat = toLat;
      this.toLon = toLon;
      this.question = question;
      this.aiSuggestion = aiSuggestion;
      this.bookingStatus = "Not booked";
      this.bookingReference = "";
      this.receiptId = "";
      this.expenseSummary = "";
      this.travelDate = null;
      this.travelTime = "";
      this.receiptDate = null;
    }

    public String getFrom() {
      return from;
    }

    public String getTo() {
      return to;
    }

    public String getMode() {
      return mode;
    }

    public double getCost() {
      return cost;
    }

    public double getHours() {
      return hours;
    }

    public double getToLat() {
      return toLat;
    }

    public double getToLon() {
      return toLon;
    }

    public String getQuestion() {
      return question;
    }

    public String getAiSuggestion() {
      return aiSuggestion;
    }

    public String getBookingStatus() {
      return bookingStatus;
    }

    public String getBookingReference() {
      return bookingReference;
    }

    public String getReceiptId() {
      return receiptId;
    }

    public String getExpenseSummary() {
      return expenseSummary;
    }

    public LocalDate getTravelDate() {
      return travelDate;
    }

    public String getTravelDateDisplay() {
      return travelDate == null ? "" : travelDate.toString();
    }

    public String getTravelTime() {
      return travelTime;
    }

    public LocalDate getReceiptDate() {
      return receiptDate;
    }

    public void setQuestion(String question) {
      this.question = question;
    }

    public void setAiSuggestion(String aiSuggestion) {
      this.aiSuggestion = aiSuggestion;
    }

    public void setBookingStatus(String bookingStatus) {
      this.bookingStatus = bookingStatus;
    }

    public void setBookingReference(String bookingReference) {
      this.bookingReference = bookingReference;
    }

    public void setReceiptId(String receiptId) {
      this.receiptId = receiptId;
    }

    public void setExpenseSummary(String expenseSummary) {
      this.expenseSummary = expenseSummary;
    }

    public void setTravelDate(LocalDate travelDate) {
      this.travelDate = travelDate;
    }

    public void setTravelTime(String travelTime) {
      this.travelTime = travelTime;
    }

    public boolean isBooked() {
      return bookingReference != null && !bookingReference.isBlank();
    }

    public boolean hasReceipt() {
      return receiptId != null && !receiptId.isBlank();
    }

    public void book(String reference) {
      bookingStatus = "Booked";
      bookingReference = reference;
    }

    public void issueReceipt(String receiptId, String expenseSummary) {
      this.receiptId = receiptId;
      this.expenseSummary = expenseSummary;
      this.receiptDate = LocalDate.now();
    }
  }

  private static void moveSelected(ListView<String> list, int delta) {
    int index = list.getSelectionModel().getSelectedIndex();
    if (index < 0) {
      return;
    }
    int next = index + delta;
    if (next < 0 || next >= list.getItems().size()) {
      return;
    }
    String item = list.getItems().remove(index);
    list.getItems().add(next, item);
    list.getSelectionModel().select(next);
  }

  private static void removeSelected(ListView<String> list) {
    int index = list.getSelectionModel().getSelectedIndex();
    if (index < 0) {
      return;
    }
    list.getItems().remove(index);
    if (!list.getItems().isEmpty()) {
      int next = Math.min(index, list.getItems().size() - 1);
      list.getSelectionModel().select(next);
    }
  }

  private static String modeIconLabel(String mode) {
    return mode == null ? "" : mode;
  }

  private static int modeRank(String mode) {
    if (mode == null) {
      return 99;
    }
    String normalized = mode.trim().toLowerCase();
    if (normalized.contains("drive")) {
      return 1;
    }
    if (normalized.contains("train")) {
      return 2;
    }
    if (normalized.contains("air")) {
      return 3;
    }
    return 99;
  }

  private static Image loadModeImage(String mode) {
    String url = modeImageUrl(mode);
    if (url == null) {
      return null;
    }
    synchronized (MODE_IMAGE_CACHE) {
      Image cached = MODE_IMAGE_CACHE.get(url);
      if (cached != null) {
        return cached;
      }
      Image image = new Image(url, 140, 0, true, true, true);
      MODE_IMAGE_CACHE.put(url, image);
      return image;
    }
  }

  private static String modeImageUrl(String mode) {
    if (mode == null) {
      return null;
    }
    String normalized = mode.trim().toLowerCase();
    if (normalized.contains("train")) {
      return "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5a/Acela_Express_train_2019.jpg/640px-Acela_Express_train_2019.jpg";
    }
    if (normalized.contains("air")) {
      return "https://upload.wikimedia.org/wikipedia/commons/thumb/4/44/Boeing_737_MAX_8_steve_lynch.jpg/640px-Boeing_737_MAX_8_steve_lynch.jpg";
    }
    if (normalized.contains("drive") || normalized.contains("car") || normalized.contains("suv")) {
      return "https://upload.wikimedia.org/wikipedia/commons/thumb/1/1f/2018_Toyota_RAV4_XLE_AWD_front_5.19.18.jpg/640px-2018_Toyota_RAV4_XLE_AWD_front_5.19.18.jpg";
    }
    return null;
  }

  private static void addRouteStop(TextField field, ListView<String> list) {
    String value = field.getText();
    if (value == null) {
      return;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return;
    }
    list.getItems().add(trimmed);
    list.getSelectionModel().selectLast();
    field.clear();
  }

  private static String buildAiSuggestion(String from, String to, DrivingMetrics driving, ModeMetrics train, ModeMetrics air) {
    long now = System.currentTimeMillis();
    if (now < aiRateLimitedUntil) {
      return buildAlertUnavailable();
    }
    String apiKey = System.getenv("OPENAI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      apiKey = readConfigValue("OPENAI_API_KEY");
      if (apiKey == null || apiKey.isBlank()) {
        return buildAlertUnavailable();
      }
    }
    String model = System.getenv("OPENAI_MODEL");
    if (model == null || model.isBlank()) {
      model = readConfigValue("OPENAI_MODEL");
    }
    if (model == null || model.isBlank()) {
      model = "gpt-4o-mini";
    }
    String prompt = "You are a travel risk assistant. Provide a concise travel alert summary for travel from "
        + from + " to " + to + ". "
        + "Return only these fields in one line, using the exact labels and order: "
        + "Weather expected: ... | Delays with travel: ... | Events in the city: ... | "
        + "Health warnings: ... | AQI and hazard information: ... "
        + "If data is unknown, say Unknown.";
    String body = "{\"model\":\"" + jsonEscape(model) + "\","
        + "\"input\":\"" + jsonEscape(prompt) + "\","
        + "\"temperature\":0.4}";
    try {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();
      for (int attempt = 0; attempt < 2; attempt++) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
          aiRateLimitedUntil = System.currentTimeMillis() + AI_RATE_LIMIT_COOLDOWN_MS;
          if (attempt == 0) {
            Thread.sleep(2000);
            continue;
          }
          return buildAlertUnavailable();
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          return buildAlertUnavailable();
        }
        String text = extractResponseText(response.body());
        if (text == null || text.isBlank()) {
          return buildAlertUnavailable();
        }
        return text.strip();
      }
      return buildAlertUnavailable();
    } catch (Exception e) {
      return buildAlertUnavailable();
    }
  }

  private static String buildAiSuggestionCached(String from, String to, DrivingMetrics driving, ModeMetrics train, ModeMetrics air) {
    String cacheKey = from + "||" + to + "||"
        + formatCurrency(driving.cost) + "||" + formatDuration(driving.hours) + "||"
        + formatCurrency(train.cost) + "||" + formatDuration(train.hours) + "||"
        + formatCurrency(air.cost) + "||" + formatDuration(air.hours);
    synchronized (AI_SUGGESTION_CACHE) {
      String cached = AI_SUGGESTION_CACHE.get(cacheKey);
      if (cached != null) {
        return cached;
      }
    }

    throttleAiRequests();
    String suggestion = buildAiSuggestion(from, to, driving, train, air);
    synchronized (AI_SUGGESTION_CACHE) {
      AI_SUGGESTION_CACHE.put(cacheKey, suggestion);
    }
    return suggestion;
  }

  private static void throttleAiRequests() {
    synchronized (AI_SUGGESTION_CACHE) {
      long now = System.currentTimeMillis();
      long waitFor = lastAiRequestAt + AI_THROTTLE_MS - now;
      if (waitFor > 0) {
        try {
          Thread.sleep(waitFor);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      lastAiRequestAt = System.currentTimeMillis();
    }
  }

  private static void askAiForSelection(TableView<RouteLegRow> table, TextField input, TextArea response,
      Label context) {
    RouteLegRow row = table.getSelectionModel().getSelectedItem();
    if (row == null && !table.getItems().isEmpty()) {
      table.getSelectionModel().selectFirst();
      row = table.getSelectionModel().getSelectedItem();
    }
    if (row == null) {
      return;
    }
    String question = input.getText();
    String normalizedQuestion = normalizeAlertQuestion(question);
    RouteLegRow selectedRow = row;
    selectedRow.setAiSuggestion("Fetching alerts...");
    updateAiPanel(selectedRow, input, response, context);
    table.refresh();
    Thread worker = new Thread(() -> {
      String answer = askAiQuestion(selectedRow, normalizedQuestion);
      Platform.runLater(() -> {
        selectedRow.setQuestion(normalizedQuestion);
        selectedRow.setAiSuggestion(answer);
        updateAiPanel(selectedRow, input, response, context);
        table.refresh();
      });
    }, "ai-qa-worker");
    worker.setDaemon(true);
    worker.start();
  }

  private static String askAiQuestion(RouteLegRow row, String question) {
    String cacheKey = row.getFrom() + "||" + row.getTo() + "||" + row.getMode() + "||" + question;
    String cached = AI_QA_CACHE.get(cacheKey);
    if (cached != null) {
      return cached;
    }
    LiveAlertData liveAlert = getLiveAlertData(row);
    long now = System.currentTimeMillis();
    if (now < aiRateLimitedUntil) {
      return buildAlertFromLiveData(liveAlert);
    }
    String apiKey = System.getenv("OPENAI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      apiKey = readConfigValue("OPENAI_API_KEY");
      if (apiKey == null || apiKey.isBlank()) {
        return buildAlertFromLiveData(liveAlert);
      }
    }
    String model = System.getenv("OPENAI_MODEL");
    if (model == null || model.isBlank()) {
      model = readConfigValue("OPENAI_MODEL");
    }
    if (model == null || model.isBlank()) {
      model = "gpt-4o-mini";
    }
    String prompt = buildAlertPrompt(row, question, liveAlert);
    String body = "{\"model\":\"" + jsonEscape(model) + "\","
        + "\"input\":\"" + jsonEscape(prompt) + "\","
        + "\"temperature\":0.4}";
    try {
      throttleAiRequests();
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();
      for (int attempt = 0; attempt < 2; attempt++) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 429) {
          aiRateLimitedUntil = System.currentTimeMillis() + AI_RATE_LIMIT_COOLDOWN_MS;
          if (attempt == 0) {
            Thread.sleep(2000);
            continue;
          }
          return buildAlertFromLiveData(liveAlert);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          return buildAlertFromLiveData(liveAlert);
        }
        String text = extractResponseText(response.body());
        if (text == null || text.isBlank()) {
          String errorMessage = extractErrorMessage(response.body());
          if (errorMessage != null && !errorMessage.isBlank()) {
            return buildAlertFromLiveData(liveAlert);
          }
          return buildAlertFromLiveData(liveAlert);
        }
        String answer = text.strip();
        if (!isAlertFallback(answer)) {
          AI_QA_CACHE.put(cacheKey, answer);
        }
        return answer;
      }
      return buildAlertFromLiveData(liveAlert);
    } catch (Exception e) {
      return buildAlertFromLiveData(liveAlert);
    }
  }

  private static String buildWikiSuggestion(String city) {
    String userAgent = readConfigValue("NOMINATIM_USER_AGENT");
    if (userAgent == null || userAgent.isBlank()) {
      userAgent = System.getenv("NOMINATIM_USER_AGENT");
    }
    if (userAgent == null || userAgent.isBlank()) {
      userAgent = "MyProject-Geovisualization (no-email)";
    }
    String title = searchWikipediaTitle(city, userAgent);
    if (title == null || title.isBlank()) {
      return "Wikipedia summary unavailable.";
    }
    String summary = fetchWikipediaSummary(title, userAgent);
    if (summary == null || summary.isBlank()) {
      return "Wikipedia summary unavailable.";
    }
    String cleaned = summary.replace("\n", " ").trim();
    if (cleaned.length() > 180) {
      return cleaned.substring(0, 177) + "...";
    }
    return cleaned;
  }

  private static void throttleGeocodeRequests() {
    synchronized (GEOCODE_CACHE) {
      long now = System.currentTimeMillis();
      long waitFor = lastGeocodeAt + GEOCODE_THROTTLE_MS - now;
      if (waitFor > 0) {
        try {
          Thread.sleep(waitFor);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      lastGeocodeAt = System.currentTimeMillis();
    }
  }

  private static String extractResponseText(String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    String outputText = matchField(body, "\"output_text\"\\s*:\\s*\"(.*?)\"");
    if (outputText != null && !outputText.isBlank()) {
      return outputText;
    }
    Pattern pattern = Pattern.compile("\"text\"\\s*:\\s*\"(.*?)\"", Pattern.DOTALL);
    Matcher matcher = pattern.matcher(body);
    String lastText = null;
    while (matcher.find()) {
      lastText = jsonUnescape(matcher.group(1));
    }
    return lastText;
  }

  private static String extractErrorMessage(String body) {
    if (body == null || body.isEmpty() || !body.contains("\"error\"")) {
      return null;
    }
    String message = matchField(body, "\"message\"\\s*:\\s*\"(.*?)\"");
    if (message == null || message.isBlank()) {
      return "unknown error";
    }
    return message;
  }

  private static String readConfigValue(String key) {
    Properties properties = new Properties();
    try (FileInputStream stream = new FileInputStream("config.properties")) {
      properties.load(stream);
      return properties.getProperty(key);
    } catch (Exception e) {
      return null;
    }
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r");
  }

  private static String jsonUnescape(String value) {
    String result = value.replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\\"", "\"")
        .replace("\\\\", "\\");
    return result;
  }

  private static String escapeHtml(String value) {
    return value.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;");
  }

  private static String formatCurrency(double cost) {
    return "$" + String.format("%.2f", Math.abs(cost));
  }

  private static String formatDuration(double hours) {
    int totalMinutes = (int) Math.round(Math.abs(hours) * 60.0);
    int wholeHours = totalMinutes / 60;
    int minutes = totalMinutes % 60;
    return wholeHours + "h " + minutes + "m";
  }

  private static List<String> buildTimeOptions() {
    List<String> options = new ArrayList<>();
    for (int hour = 0; hour < 24; hour++) {
      options.add(String.format("%02d:00", hour));
      options.add(String.format("%02d:30", hour));
    }
    return options;
  }

  private static List<String> buildMonthLabels() {
    List<String> labels = new ArrayList<>();
    labels.add("Jan");
    labels.add("Feb");
    labels.add("Mar");
    labels.add("Apr");
    labels.add("May");
    labels.add("Jun");
    labels.add("Jul");
    labels.add("Aug");
    labels.add("Sep");
    labels.add("Oct");
    labels.add("Nov");
    labels.add("Dec");
    return labels;
  }

  private static String buildMonthLabel(LocalDate date) {
    if (date == null) {
      return "Unknown";
    }
    int index = date.getMonthValue() - 1;
    List<String> labels = buildMonthLabels();
    if (index < 0 || index >= labels.size()) {
      return "Unknown";
    }
    return labels.get(index);
  }

  private static LocalDate resolveReportDate(RouteLegRow row) {
    if (row == null) {
      return null;
    }
    LocalDate date = row.getTravelDate();
    if (date != null) {
      return date;
    }
    return row.getReceiptDate();
  }

  private static String buildTripShortform(RouteLegRow row) {
    if (row == null) {
      return "";
    }
    LocalDate date = resolveReportDate(row);
    String dateLabel = date == null ? "No date" : date.toString();
    String time = row.getTravelTime();
    String timeLabel = time == null || time.isBlank() ? "" : " " + time.trim();
    return buildMonthLabel(date) + " | " + formatCurrency(row.getCost())
        + " | " + row.getFrom() + " -> " + row.getTo()
        + " | " + row.getMode()
        + " | " + dateLabel + timeLabel;
  }

  private static String buildReportDetail(RouteLegRow row) {
    if (row == null) {
      return "";
    }
    StringBuilder detail = new StringBuilder();
    detail.append("Trip: ").append(row.getFrom()).append(" -> ").append(row.getTo()).append("\n");
    detail.append("Mode: ").append(row.getMode()).append("\n");
    detail.append("Cost: ").append(formatCurrency(row.getCost())).append("\n");
    LocalDate date = resolveReportDate(row);
    String time = row.getTravelTime();
    if (date != null) {
      detail.append("Travel date/time: ").append(date);
      if (time != null && !time.isBlank()) {
        detail.append(" ").append(time.trim());
      }
      detail.append("\n");
    }
    if (row.getBookingReference() != null && !row.getBookingReference().isBlank()) {
      detail.append("Booking ref: ").append(row.getBookingReference()).append("\n");
    }
    if (row.getReceiptId() != null && !row.getReceiptId().isBlank()) {
      detail.append("Receipt ID: ").append(row.getReceiptId()).append("\n");
    }
    if (row.getExpenseSummary() != null && !row.getExpenseSummary().isBlank()) {
      detail.append("Expense summary: ").append(row.getExpenseSummary());
    }
    return detail.toString().trim();
  }

  private static Node buildReportIcon() {
    Rectangle frame = new Rectangle(20, 16);
    frame.setArcWidth(3);
    frame.setArcHeight(3);
    frame.setFill(Color.TRANSPARENT);
    frame.setStroke(Color.web("#1f4a7a"));

    Rectangle bar1 = new Rectangle(4, 6, Color.web("#2f7ed8"));
    Rectangle bar2 = new Rectangle(4, 10, Color.web("#1e9248"));
    Rectangle bar3 = new Rectangle(4, 8, Color.web("#d77c2f"));
    HBox bars = new HBox(2, bar1, bar2, bar3);
    bars.setAlignment(Pos.BOTTOM_CENTER);

    StackPane icon = new StackPane(frame, bars);
    icon.setMinSize(20, 16);
    return icon;
  }

  private static void updateMonthlySpend(TableView<RouteLegRow> table, StackedBarChart<String, Number> chart,
      ListView<RouteLegRow> reportList, TextArea reportDetails) {
    if (table == null || chart == null || reportList == null || reportDetails == null) {
      return;
    }
    RouteLegRow selected = reportList.getSelectionModel().getSelectedItem();
    List<RouteLegRow> receipts = new ArrayList<>();
    chart.getData().clear();
    for (RouteLegRow row : table.getItems()) {
      if (row == null || !row.hasReceipt()) {
        continue;
      }
      LocalDate date = resolveReportDate(row);
      if (date == null) {
        continue;
      }
      RouteLegRow receipt = row;
      receipts.add(receipt);
      String monthLabel = buildMonthLabel(date);
      XYChart.Series<String, Number> series = new XYChart.Series<>();
      XYChart.Data<String, Number> data = new XYChart.Data<>(monthLabel, Math.abs(receipt.getCost()));
      series.getData().add(data);
      chart.getData().add(series);
      String shortInfo = buildTripShortform(receipt);
      String detail = buildReportDetail(receipt);
      data.nodeProperty().addListener((obs, oldNode, newNode) -> {
        if (newNode == null) {
          return;
        }
        Tooltip.install(newNode, new Tooltip(shortInfo));
        newNode.setOnMouseEntered(event -> newNode.setStyle("-fx-opacity: 0.85; -fx-border-color: #1f4a7a; -fx-border-width: 1;"));
        newNode.setOnMouseExited(event -> newNode.setStyle(""));
        newNode.setOnMouseClicked(event -> {
          reportDetails.setText(detail);
          reportList.getSelectionModel().select(receipt);
        });
      });
    }
    reportList.getItems().setAll(receipts);
    if (selected != null && receipts.contains(selected)) {
      reportList.getSelectionModel().select(selected);
      reportDetails.setText(buildReportDetail(selected));
    } else {
      reportDetails.setText("");
    }
  }

  private static void triggerSpendUpdate(TableView<RouteLegRow> table) {
    if (table == null) {
      return;
    }
    Object handler = table.getUserData();
    if (handler instanceof Runnable) {
      ((Runnable) handler).run();
    }
  }

  private static void updateAiPanel(RouteLegRow row, TextField questionField, TextArea responseArea, Label context) {
    if (row == null) {
      context.setText("Selected leg: None");
      questionField.setText("");
      responseArea.setText("");
      return;
    }
    context.setText("Selected leg: " + row.getFrom() + " -> " + row.getTo() + " (" + row.getMode() + ")");
    String question = row.getQuestion();
    questionField.setText(question == null ? "" : question);
    String response = row.getAiSuggestion();
    responseArea.setText(response == null ? "" : response);
  }

  private static void populateLiveAlerts(TableView<RouteLegRow> table) {
    if (table == null || table.getItems().isEmpty()) {
      return;
    }
    Thread worker = new Thread(() -> {
      for (RouteLegRow row : table.getItems()) {
        if (row == null) {
          continue;
        }
        String existing = row.getAiSuggestion();
        if (existing != null && !isAlertFallback(existing)) {
          continue;
        }
        String question = row.getQuestion();
        String normalizedQuestion = question == null || question.isBlank() ? null : question.trim();
        row.setAiSuggestion(askAiQuestion(row, normalizedQuestion));
      }
      Platform.runLater(table::refresh);
    }, "live-alerts-worker");
    worker.setDaemon(true);
    worker.start();
  }

  private static Map<String, List<String>> buildSbaOffices() {
    Map<String, List<String>> offices = new LinkedHashMap<>();
    offices.put(normalizePlaceKey("Washington, DC"),
        List.of("U.S. SBA Washington Metropolitan Area District Office"));
    offices.put(normalizePlaceKey("Baltimore, MD"),
        List.of("U.S. SBA Baltimore District Office"));
    offices.put(normalizePlaceKey("Philadelphia, PA"),
        List.of("U.S. SBA Eastern Pennsylvania District Office"));
    offices.put(normalizePlaceKey("New York, NY"),
        List.of("U.S. SBA New York District Office"));
    return offices;
  }

  private static void refreshAgendaCities(ListView<String> routeList, ComboBox<String> citySelect,
      ListView<String> agendaList, ComboBox<String> sbaSelect, Label sbaLabel) {
    String current = citySelect.getValue();
    citySelect.getItems().setAll(routeList.getItems());
    if (current != null && citySelect.getItems().contains(current)) {
      citySelect.setValue(current);
    } else if (!citySelect.getItems().isEmpty()) {
      citySelect.getSelectionModel().selectFirst();
    } else {
      citySelect.setValue(null);
    }
    updateAgendaForCity(citySelect, agendaList, sbaSelect, sbaLabel);
  }

  private static void updateAgendaForCity(ComboBox<String> citySelect, ListView<String> agendaList,
      ComboBox<String> sbaSelect, Label sbaLabel) {
    String city = citySelect.getValue();
    if (city == null || city.isBlank()) {
      agendaList.setItems(FXCollections.observableArrayList());
      sbaSelect.getItems().clear();
      sbaSelect.setDisable(true);
      sbaLabel.setText("No SBA office selected.");
      return;
    }
    agendaList.setItems(agendaItemsForCity(city));
    List<String> offices = sbaOfficesForCity(city);
    sbaSelect.getItems().setAll(offices);
    sbaSelect.setDisable(offices.isEmpty());
    String selected = SBA_SELECTIONS.get(normalizePlaceKey(city));
    if (selected != null && offices.contains(selected)) {
      sbaSelect.setValue(selected);
      sbaLabel.setText(selected);
    } else if (!offices.isEmpty()) {
      String firstOffice = offices.get(0);
      sbaSelect.setValue(firstOffice);
      sbaLabel.setText(firstOffice);
      SBA_SELECTIONS.put(normalizePlaceKey(city), firstOffice);
    } else {
      sbaSelect.setValue(null);
      sbaLabel.setText("No local SBA office listed.");
    }
  }

  private static ObservableList<String> agendaItemsForCity(String city) {
    if (city == null) {
      return FXCollections.observableArrayList();
    }
    String key = normalizePlaceKey(city);
    synchronized (AGENDA_ITEMS) {
      ObservableList<String> items = AGENDA_ITEMS.get(key);
      if (items == null) {
        items = FXCollections.observableArrayList();
        AGENDA_ITEMS.put(key, items);
      }
      return items;
    }
  }

  private static List<String> sbaOfficesForCity(String city) {
    if (city == null) {
      return List.of();
    }
    List<String> offices = SBA_OFFICES.get(normalizePlaceKey(city));
    if (offices == null) {
      return List.of();
    }
    return offices;
  }

  private static void addAgendaItem(TextField field, ListView<String> list) {
    if (field == null || list == null) {
      return;
    }
    String value = field.getText();
    if (value == null) {
      return;
    }
    String trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return;
    }
    list.getItems().add(trimmed);
    list.getSelectionModel().select(trimmed);
    field.clear();
  }

  private static void removeAgendaItem(ListView<String> list) {
    if (list == null) {
      return;
    }
    int index = list.getSelectionModel().getSelectedIndex();
    if (index < 0) {
      return;
    }
    list.getItems().remove(index);
  }

  private static void moveAgendaItem(ListView<String> list, int delta) {
    if (list == null) {
      return;
    }
    int index = list.getSelectionModel().getSelectedIndex();
    if (index < 0) {
      return;
    }
    int next = index + delta;
    if (next < 0 || next >= list.getItems().size()) {
      return;
    }
    String item = list.getItems().remove(index);
    list.getItems().add(next, item);
    list.getSelectionModel().select(next);
  }

  private static void addSbaVisit(ComboBox<String> citySelect, ListView<String> agendaList,
      ComboBox<String> sbaSelect, boolean align) {
    if (agendaList == null || sbaSelect == null || citySelect == null) {
      return;
    }
    String city = citySelect.getValue();
    String office = sbaSelect.getValue();
    if ((office == null || office.isBlank()) && !sbaSelect.getItems().isEmpty()) {
      office = sbaSelect.getItems().get(0);
      sbaSelect.setValue(office);
    }
    if (city == null || office == null || office.isBlank()) {
      return;
    }
    String item = "Conference: SBA office visit - " + office;
    if (align) {
      agendaList.getItems().remove(item);
      agendaList.getItems().add(0, item);
    } else if (!agendaList.getItems().contains(item)) {
      agendaList.getItems().add(item);
    }
    agendaList.getSelectionModel().select(item);
    SBA_SELECTIONS.put(normalizePlaceKey(city), office);
  }

  private static String normalizeAlertQuestion(String question) {
    if (question == null || question.isBlank()) {
      return "Provide a concise travel alert summary.";
    }
    return question.trim();
  }

  private static String buildAlertPlaceholder() {
    return buildAlertFallback("Unknown");
  }

  private static String buildAlertPrompt(RouteLegRow row, String question, LiveAlertData liveAlert) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("You are a travel risk assistant. Provide a concise travel alert summary for the route leg: ")
        .append(row.getFrom()).append(" to ").append(row.getTo()).append(" via ").append(row.getMode()).append(". ")
        .append("Return only these fields in one line, using the exact labels and order: ")
        .append("Weather expected: ... | Delays with travel: ... | Events in the city: ... | ")
        .append("Health warnings: ... | AQI and hazard information: ... ")
        .append("If data is unknown, say Unknown.");
    if (liveAlert != null) {
      if (liveAlert.weather != null && !liveAlert.weather.isBlank()) {
        prompt.append(" Known weather: ").append(liveAlert.weather).append(".");
      }
      if (liveAlert.aqi != null && !liveAlert.aqi.isBlank()) {
        prompt.append(" Known AQI: ").append(liveAlert.aqi).append(".");
      }
    }
    if (question != null && !question.isBlank()) {
      prompt.append(" User note: ").append(question).append(".");
    }
    return prompt.toString();
  }

  private static String buildAlertUnavailable() {
    return buildAlertFallback("Unavailable");
  }

  private static String buildAlertFallback(String value) {
    String safeValue = value == null || value.isBlank() ? "Unknown" : value;
    return buildAlertFields(safeValue, safeValue, safeValue, safeValue, safeValue);
  }

  private static String buildAlertFields(String weather, String delays, String events, String health, String aqi) {
    String weatherValue = safeAlertField(weather);
    String delaysValue = safeAlertField(delays);
    String eventsValue = safeAlertField(events);
    String healthValue = safeAlertField(health);
    String aqiValue = safeAlertField(aqi);
    return "Weather expected: " + weatherValue
        + " | Delays with travel: " + delaysValue
        + " | Events in the city: " + eventsValue
        + " | Health warnings: " + healthValue
        + " | AQI and hazard information: " + aqiValue;
  }

  private static String safeAlertField(String value) {
    if (value == null || value.isBlank()) {
      return "Unknown";
    }
    return value.trim();
  }

  private static LiveAlertData getLiveAlertData(RouteLegRow row) {
    if (row == null) {
      return null;
    }
    String city = row.getTo();
    if (city == null || city.isBlank()) {
      return null;
    }
    String key = normalizePlaceKey(city);
    long now = System.currentTimeMillis();
    synchronized (LIVE_ALERT_CACHE) {
      LiveAlertData cached = LIVE_ALERT_CACHE.get(key);
      if (cached != null && now - cached.fetchedAt < LIVE_ALERT_TTL_MS) {
        return cached;
      }
    }
    double lat = row.getToLat();
    double lon = row.getToLon();
    if (Double.isNaN(lat) || Double.isNaN(lon) || (lat == 0.0 && lon == 0.0)) {
      double[] latLon = geocode(city);
      if (latLon == null) {
        return null;
      }
      lat = latLon[0];
      lon = latLon[1];
    }
    String weather = fetchOpenMeteoWeather(lat, lon);
    String aqi = fetchOpenMeteoAqi(lat, lon);
    LiveAlertData data = new LiveAlertData(weather, aqi, now);
    synchronized (LIVE_ALERT_CACHE) {
      LIVE_ALERT_CACHE.put(key, data);
    }
    return data;
  }

  private static String fetchOpenMeteoWeather(double lat, double lon) {
    String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
        + "&current=temperature_2m,weather_code,wind_speed_10m&temperature_unit=fahrenheit";
    try {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(8))
          .build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return "Unknown";
      }
      String body = response.body();
      String tempValue = matchField(body, "\"temperature_2m\"\\s*:\\s*([0-9.+-]+)");
      String windValue = matchField(body, "\"wind_speed_10m\"\\s*:\\s*([0-9.+-]+)");
      String codeValue = matchField(body, "\"weather_code\"\\s*:\\s*([0-9]+)");
      StringBuilder summary = new StringBuilder();
      if (tempValue != null) {
        summary.append(formatNumber(tempValue)).append("F");
      }
      String codeDesc = weatherCodeDescription(codeValue);
      if (codeDesc != null) {
        if (summary.length() > 0) {
          summary.append(", ");
        }
        summary.append(codeDesc);
      }
      if (windValue != null) {
        if (summary.length() > 0) {
          summary.append(", ");
        }
        summary.append("Wind ").append(formatNumber(windValue)).append(" mph");
      }
      if (summary.length() == 0) {
        return "Unknown";
      }
      return summary.toString();
    } catch (Exception e) {
      return "Unknown";
    }
  }

  private static String fetchOpenMeteoAqi(double lat, double lon) {
    String url = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + lat + "&longitude=" + lon
        + "&current=us_aqi,pm2_5,pm10";
    try {
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(8))
          .build();
      HttpRequest request = HttpRequest.newBuilder(URI.create(url))
          .timeout(Duration.ofSeconds(10))
          .GET()
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return "Unknown";
      }
      String body = response.body();
      String aqiValue = matchField(body, "\"us_aqi\"\\s*:\\s*([0-9.+-]+)");
      String pm25Value = matchField(body, "\"pm2_5\"\\s*:\\s*([0-9.+-]+)");
      String pm10Value = matchField(body, "\"pm10\"\\s*:\\s*([0-9.+-]+)");
      StringBuilder summary = new StringBuilder();
      if (aqiValue != null) {
        summary.append("US AQI ").append(formatNumber(aqiValue));
      }
      if (pm25Value != null) {
        if (summary.length() > 0) {
          summary.append(", ");
        }
        summary.append("PM2.5 ").append(formatNumber(pm25Value));
      }
      if (pm10Value != null) {
        if (summary.length() > 0) {
          summary.append(", ");
        }
        summary.append("PM10 ").append(formatNumber(pm10Value));
      }
      if (summary.length() == 0) {
        return "Unknown";
      }
      return summary.toString();
    } catch (Exception e) {
      return "Unknown";
    }
  }

  private static String formatNumber(String value) {
    if (value == null) {
      return "";
    }
    try {
      double number = Double.parseDouble(value);
      if (Math.abs(number) >= 100) {
        return String.format("%.0f", number);
      }
      return String.format("%.1f", number);
    } catch (NumberFormatException e) {
      return value;
    }
  }

  private static String weatherCodeDescription(String codeValue) {
    if (codeValue == null) {
      return null;
    }
    switch (codeValue) {
      case "0":
        return "Clear";
      case "1":
      case "2":
      case "3":
        return "Partly cloudy";
      case "45":
      case "48":
        return "Fog";
      case "51":
      case "53":
      case "55":
        return "Drizzle";
      case "61":
      case "63":
      case "65":
        return "Rain";
      case "71":
      case "73":
      case "75":
        return "Snow";
      case "80":
      case "81":
      case "82":
        return "Showers";
      case "95":
        return "Thunderstorm";
      case "96":
      case "99":
        return "Thunderstorm hail";
      default:
        return "Weather code " + codeValue;
    }
  }

  private static String buildAlertFromLiveData(LiveAlertData liveAlert) {
    String weather = liveAlert != null ? liveAlert.weather : "Unknown";
    String aqi = liveAlert != null ? liveAlert.aqi : "Unknown";
    return buildAlertFields(weather, "No live delay data", "No live events data", "No live health data", aqi);
  }

  private static boolean isAlertFallback(String value) {
    if (value == null) {
      return true;
    }
    return value.contains("No live") || value.contains("Unavailable");
  }

  private static String generateBookingReference(RouteLegRow row) {
    String fromCode = compactCityCode(row.getFrom());
    String toCode = compactCityCode(row.getTo());
    int suffix = 1000 + (int) (Math.random() * 9000);
    return "BK-" + fromCode + "-" + toCode + "-" + suffix;
  }

  private static String generateReceiptId(RouteLegRow row) {
    String date = LocalDate.now().toString().replace("-", "");
    int suffix = 10000 + (int) (Math.random() * 90000);
    return "RCPT-" + date + "-" + suffix;
  }

  private static String buildExpenseSummary(RouteLegRow row, String receiptId) {
    String date = LocalDate.now().toString();
    String summary = "Date: " + date
        + " | " + row.getFrom() + " to " + row.getTo()
        + " | " + row.getMode()
        + " | " + formatCurrency(row.getCost())
        + " | " + formatDuration(row.getHours());
    if (row.getTravelDate() != null) {
      String travelTime = row.getTravelTime();
      if (travelTime == null || travelTime.isBlank()) {
        summary += " | Travel " + row.getTravelDate();
      } else {
        summary += " | Travel " + row.getTravelDate() + " " + travelTime.trim();
      }
    }
    if (row.getBookingReference() != null && !row.getBookingReference().isBlank()) {
      summary += " | Booking " + row.getBookingReference();
    }
    if (receiptId != null && !receiptId.isBlank()) {
      summary += " | Receipt " + receiptId;
    }
    return summary;
  }

  private static String compactCityCode(String city) {
    if (city == null) {
      return "UNK";
    }
    String cleaned = city.replaceAll("[^A-Za-z]", " ").trim();
    if (cleaned.isEmpty()) {
      return "UNK";
    }
    String[] parts = cleaned.split("\\s+");
    StringBuilder code = new StringBuilder();
    for (String part : parts) {
      if (!part.isEmpty() && code.length() < 3) {
        code.append(Character.toUpperCase(part.charAt(0)));
      }
    }
    if (code.length() < 3) {
      String first = parts[0].toUpperCase();
      for (int i = 1; i < first.length() && code.length() < 3; i++) {
        code.append(first.charAt(i));
      }
    }
    return code.toString();
  }

  private static final class WrappingTableCell extends TableCell<RouteLegRow, String> {
    private final Text text = new Text();

    private WrappingTableCell() {
      text.wrappingWidthProperty().bind(widthProperty().subtract(10));
      setGraphic(text);
    }

    @Override
    protected void updateItem(String item, boolean empty) {
      super.updateItem(item, empty);
      if (empty || item == null) {
        text.setText("");
      } else {
        text.setText(item);
      }
    }
  }
}
