import java.awt.Desktop;
import java.io.FileInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Properties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import javafx.scene.web.WebEngine;
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
  private static final Map<String, List<Restaurant>> RESTAURANT_CACHE = new LinkedHashMap<>();
  private static final Map<String, List<HotelOption>> HOTEL_CACHE = new LinkedHashMap<>();
  private static final Map<String, BusinessTripProfile> BUSINESS_TRIP_PROFILES = new LinkedHashMap<>();
  private static final Map<String, Image> MODE_IMAGE_CACHE = new LinkedHashMap<>();
  private static final Map<String, ObservableList<String>> AGENDA_ITEMS = new LinkedHashMap<>();
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
    final List<String>[] currentRouteCities = new List[]{new ArrayList<>(routeCities)};
    final String[] contentMode = {"overview"};
    final String[] selectedContentCity = {routeCities.isEmpty() ? "" : routeCities.get(0)};

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
    attractionsView.getEngine().setJavaScriptEnabled(true);
    attractionsView.setPrefHeight(200);
    Label contentPaneTitle = new Label("Route Explorer");
    Button exploreScreen = new Button("Main");
    Button bookingScreen = new Button("Booking.com");
    Button guideScreen = new Button("City Guide");
    Button backToMain = new Button("Back to main");
    backToMain.setStyle("-fx-background-color: rgba(255,255,255,0.9);"
        + "-fx-border-color: #cccccc;"
        + "-fx-border-radius: 4;"
        + "-fx-background-radius: 4;");
    HBox contentToolbar = new HBox(8, contentPaneTitle, exploreScreen, bookingScreen, guideScreen, backToMain);
    contentToolbar.setAlignment(Pos.CENTER_LEFT);
    HBox.setHgrow(contentPaneTitle, Priority.ALWAYS);
    VBox attractionsPane = new VBox(8, contentToolbar, attractionsView);
    VBox.setVgrow(attractionsView, Priority.ALWAYS);

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
    Label bookingDeskContext = new Label("Selected leg: None");
    TextField bookingVendorField = new TextField();
    bookingVendorField.setPromptText("Booked property or vendor");
    TextField bookingBaseCostField = new TextField();
    bookingBaseCostField.setPromptText("Booked base cost");
    TextField bookingFeesField = new TextField();
    bookingFeesField.setPromptText("Taxes and fees");
    TextField bookingReferenceField = new TextField();
    bookingReferenceField.setPromptText("Booking reference");
    TextArea bookingNotesArea = new TextArea();
    bookingNotesArea.setPromptText("Add the exact room, fare class, cancellation terms, or client billing note.");
    bookingNotesArea.setPrefRowCount(3);
    Button openBookingLink = new Button("Open Booking.com");
    Button saveBookingSync = new Button("Sync Booking");
    Button clearBookingSync = new Button("Clear Sync");
    TextArea routeBriefArea = new TextArea();
    routeBriefArea.setEditable(false);
    routeBriefArea.setWrapText(true);
    routeBriefArea.setPrefRowCount(6);
    HBox bookingCostBar = new HBox(8, bookingBaseCostField, bookingFeesField, bookingReferenceField);
    HBox bookingSyncActions = new HBox(8, openBookingLink, saveBookingSync, clearBookingSync);
    VBox bookingPane = new VBox(6,
        new Label("Booking Sync"),
        bookingDeskContext,
        bookingVendorField,
        bookingCostBar,
        bookingNotesArea,
        bookingSyncActions,
        new Label("Travel Brief"),
        routeBriefArea);
    VBox pricePane = new VBox(8, travelControls, priceTable, bookingPane);
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
    reportButton.setTooltip(new Tooltip("Toggle drilldown details"));
    ComboBox<String> reportFilter = new ComboBox<>();
    reportFilter.getItems().addAll("Receipts", "Booked Travel", "Pending Receipts", "All Legs");
    reportFilter.getSelectionModel().selectFirst();
    Label reportHint = new Label("Drill down, print, and review booked-vs-estimate variance.");
    HBox reportHeader = new HBox(8, new Label("Expense Reports"), reportButton, reportFilter, reportHint);
    reportHeader.setAlignment(Pos.CENTER_LEFT);
    Label reportSummary = new Label("Booked total: $0.00 | Receipts: 0 | Pending receipts: 0");
    Button printReportButton = new Button("Print");
    Button exportReportButton = new Button("Open Summary");
    HBox reportActions = new HBox(8, reportSummary, printReportButton, exportReportButton);
    reportActions.setAlignment(Pos.CENTER_LEFT);

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

    VBox reportingPane = new VBox(8, reportHeader, reportActions, spendChart, reportDetailsBox);
    VBox.setVgrow(spendChart, Priority.ALWAYS);

    Label agendaTitle = new Label("Business Traveler Workspace");
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
    TextArea meetingGoalsArea = new TextArea();
    meetingGoalsArea.setPromptText("Meeting goals, client objectives, and decision targets");
    meetingGoalsArea.setPrefRowCount(3);
    TextArea stakeholderArea = new TextArea();
    stakeholderArea.setPromptText("Stakeholders, venues, and follow-up owners");
    stakeholderArea.setPrefRowCount(3);
    TextArea logisticsArea = new TextArea();
    logisticsArea.setPromptText("Hotel, ground transport, workspace, and reimbursement notes");
    logisticsArea.setPrefRowCount(3);
    VBox businessPane = new VBox(8, agendaTitle, agendaCitySelect, agendaList, agendaInputBar,
        agendaOrderBar, new Label("Meeting Goals"), meetingGoalsArea,
        new Label("Stakeholders and Venues"), stakeholderArea,
        new Label("Travel Logistics"), logisticsArea);
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
    installExternalLinkHandler(attractionsView,
        () -> loadContentPane(attractionsView, contentPaneTitle, currentRouteCities[0], selectedContentCity[0],
            contentMode[0]));
    loadContentPane(attractionsView, contentPaneTitle, routeCities, selectedContentCity[0], contentMode[0]);

    moveUp.setOnAction(event -> moveSelected(routeList, -1));
    moveDown.setOnAction(event -> moveSelected(routeList, 1));
    removeStop.setOnAction(event -> removeSelected(routeList));
    recalc.setOnAction(event -> {
      List<String> ordered = new ArrayList<>(routeList.getItems());
      currentRouteCities[0] = ordered;
      selectedContentCity[0] = chooseActiveCity(ordered, selectedContentCity[0]);
      loadRouteData(webView, status, priceTable, ordered);
      loadContentPane(attractionsView, contentPaneTitle, ordered, selectedContentCity[0], contentMode[0]);
    });
    addStop.setOnAction(event -> addRouteStop(routeSearch, routeList));
    routeSearch.setOnAction(event -> addRouteStop(routeSearch, routeList));
    backToMain.setOnAction(event -> {
      contentMode[0] = "overview";
      loadContentPane(attractionsView, contentPaneTitle, currentRouteCities[0], selectedContentCity[0], contentMode[0]);
    });
    exploreScreen.setOnAction(event -> {
      contentMode[0] = "overview";
      loadContentPane(attractionsView, contentPaneTitle, currentRouteCities[0], selectedContentCity[0], contentMode[0]);
    });
    bookingScreen.setOnAction(event -> {
      contentMode[0] = "booking";
      loadContentPane(attractionsView, contentPaneTitle, currentRouteCities[0], selectedContentCity[0], contentMode[0]);
    });
    guideScreen.setOnAction(event -> {
      contentMode[0] = "guide";
      loadContentPane(attractionsView, contentPaneTitle, currentRouteCities[0], selectedContentCity[0], contentMode[0]);
    });

    Runnable spendUpdater = () -> updateMonthlySpend(priceTable, spendChart, reportList, reportDetails,
        reportSummary, reportFilter.getValue());
    priceTable.setUserData(spendUpdater);
    updateMonthlySpend(priceTable, spendChart, reportList, reportDetails, reportSummary, reportFilter.getValue());

    priceTable.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
      if (newRow == null) {
        travelDatePicker.setValue(null);
        travelTimePicker.setValue(null);
        populateBookingSyncFields(null, bookingDeskContext, bookingVendorField, bookingBaseCostField,
            bookingFeesField, bookingReferenceField, bookingNotesArea);
        routeBriefArea.setText("Select a route leg to see live weather, attractions, and booking sync fields.");
        return;
      }
      travelDatePicker.setValue(newRow.getTravelDate());
      String time = newRow.getTravelTime();
      if (time == null || time.isBlank()) {
        travelTimePicker.setValue(null);
      } else {
        travelTimePicker.setValue(time);
      }
      populateBookingSyncFields(newRow, bookingDeskContext, bookingVendorField, bookingBaseCostField,
          bookingFeesField, bookingReferenceField, bookingNotesArea);
      loadTravelBrief(newRow, routeBriefArea);
    });
    loadTravelBrief(priceTable.getSelectionModel().getSelectedItem(), routeBriefArea);

    routeList.getSelectionModel().selectedItemProperty().addListener((obs, oldCity, newCity) -> {
      if (newCity == null || newCity.isBlank()) {
        return;
      }
      selectedContentCity[0] = newCity;
      loadContentPane(attractionsView, contentPaneTitle, currentRouteCities[0], selectedContentCity[0], contentMode[0]);
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
    reportFilter.setOnAction(event -> updateMonthlySpend(priceTable, spendChart, reportList, reportDetails,
        reportSummary, reportFilter.getValue()));
    printReportButton.setOnAction(event -> printReportView(reportList.getItems(), reportFilter.getValue()));
    exportReportButton.setOnAction(event -> openReportSummary(reportList.getItems(), reportFilter.getValue()));

    reportList.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
      if (newRow == null) {
        reportDetails.setText("");
      } else {
        reportDetails.setText(buildReportDetail(newRow));
      }
    });

    refreshAgendaCities(routeList, agendaCitySelect, agendaList);
    agendaCitySelect.setOnAction(event -> {
      updateAgendaForCity(agendaCitySelect, agendaList);
      loadBusinessTripProfile(agendaCitySelect.getValue(), meetingGoalsArea, stakeholderArea, logisticsArea);
    });
    routeList.getItems().addListener((ListChangeListener<String>) change -> {
      currentRouteCities[0] = new ArrayList<>(routeList.getItems());
      selectedContentCity[0] = chooseActiveCity(currentRouteCities[0], selectedContentCity[0]);
      loadContentPane(attractionsView, contentPaneTitle, currentRouteCities[0], selectedContentCity[0], contentMode[0]);
      refreshAgendaCities(routeList, agendaCitySelect, agendaList);
      loadBusinessTripProfile(agendaCitySelect.getValue(), meetingGoalsArea, stakeholderArea, logisticsArea);
    });

    addAgenda.setOnAction(event -> addAgendaItem(agendaInput, agendaList));
    agendaInput.setOnAction(event -> addAgendaItem(agendaInput, agendaList));
    removeAgenda.setOnAction(event -> removeAgendaItem(agendaList));
    agendaUp.setOnAction(event -> moveAgendaItem(agendaList, -1));
    agendaDown.setOnAction(event -> moveAgendaItem(agendaList, 1));

    loadBusinessTripProfile(agendaCitySelect.getValue(), meetingGoalsArea, stakeholderArea, logisticsArea);
    meetingGoalsArea.textProperty().addListener((obs, oldText, newText) ->
        saveBusinessTripProfile(agendaCitySelect.getValue(), meetingGoalsArea, stakeholderArea, logisticsArea));
    stakeholderArea.textProperty().addListener((obs, oldText, newText) ->
        saveBusinessTripProfile(agendaCitySelect.getValue(), meetingGoalsArea, stakeholderArea, logisticsArea));
    logisticsArea.textProperty().addListener((obs, oldText, newText) ->
        saveBusinessTripProfile(agendaCitySelect.getValue(), meetingGoalsArea, stakeholderArea, logisticsArea));
    openBookingLink.setOnAction(event -> openSelectedBookingLink(priceTable));
    saveBookingSync.setOnAction(event -> saveBookingSync(priceTable, bookingVendorField, bookingBaseCostField,
        bookingFeesField, bookingReferenceField, bookingNotesArea, routeBriefArea));
    clearBookingSync.setOnAction(event -> clearBookingSync(priceTable, bookingDeskContext, bookingVendorField,
        bookingBaseCostField, bookingFeesField, bookingReferenceField, bookingNotesArea, routeBriefArea));
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
      });
    }, "route-data-worker");
    worker.setDaemon(true);
    worker.start();
  }

  private static void loadContentPane(WebView webView, Label titleLabel, List<String> cities, String selectedCity,
      String mode) {
    List<String> citySnapshot = cities == null ? List.of() : new ArrayList<>(cities);
    String activeCity = chooseActiveCity(citySnapshot, selectedCity);
    if ("booking".equals(mode)) {
      Thread worker = new Thread(() -> {
        String html = buildBookingHubHtml(activeCity);
        Platform.runLater(() -> {
          titleLabel.setText("Booking Hub: " + activeCity);
          webView.getEngine().loadContent(html);
        });
      }, "booking-hub-worker");
      worker.setDaemon(true);
      worker.start();
      return;
    }
    if ("guide".equals(mode)) {
      Thread worker = new Thread(() -> {
        String html = buildCityGuideHtml(activeCity);
        Platform.runLater(() -> {
          titleLabel.setText("City Guide: " + activeCity);
          webView.getEngine().loadContent(html);
        });
      }, "city-guide-worker");
      worker.setDaemon(true);
      worker.start();
      return;
    }
    Thread worker = new Thread(() -> {
      List<Attraction> attractions = fetchAttractions(citySnapshot);
      String html = buildAttractionsHtml(attractions, activeCity, citySnapshot);
      Platform.runLater(() -> {
        titleLabel.setText("Route Explorer: " + activeCity);
        webView.getEngine().loadContent(html);
      });
    }, "content-pane-worker");
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

  private static String buildAttractionsHtml(List<Attraction> attractions, String selectedCity, List<String> routeCities) {
    Map<String, List<Attraction>> byCity = new LinkedHashMap<>();
    for (Attraction attraction : attractions) {
      byCity.computeIfAbsent(attraction.city, key -> new ArrayList<>()).add(attraction);
    }
    String activeCity = chooseActiveCity(routeCities, selectedCity);
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset='utf-8'/>")
        .append("<style>")
        .append("body{font-family:Arial,sans-serif;margin:0;background:linear-gradient(180deg,#eef5ff,#f7f9fc);color:#17324d;}")
        .append(".hero{padding:14px 16px 8px 16px;background:#ffffff;border-bottom:1px solid #d7e2ee;}")
        .append(".hero h1{margin:0 0 4px 0;font-size:18px;}")
        .append(".hero p{margin:0;color:#516b86;font-size:12px;}")
        .append(".actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px;}")
        .append(".action{display:inline-block;padding:8px 12px;border-radius:999px;text-decoration:none;font-size:12px;font-weight:bold;}")
        .append(".booking{background:#003b95;color:#fff;}")
        .append(".guide{background:#0f766e;color:#fff;}")
        .append(".news{background:#17324d;color:#fff;}")
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
        .append(".meta{padding:0 10px 8px 10px;font-size:11px;color:#5d7186;}")
        .append("</style></head><body>");

    html.append("<div class='hero'>")
        .append("<h1>").append(escapeHtml(activeCity)).append("</h1>")
        .append("<p>Use the buttons above to switch between the in-app explorer, Booking.com, and the built-in city guide for this city.</p>")
        .append("<div class='actions'>")
        .append("<a class='action booking' href='").append(externalLinkHref(buildBookingUrl(activeCity))).append("'>Book stay</a>")
        .append("<a class='action guide' href='").append(externalLinkHref(buildGuideSearchUrl(activeCity + " restaurants attractions"))).append("'>City guide search</a>")
        .append("<a class='action news' href='").append(externalLinkHref(buildCityNewsUrl(activeCity))).append("'>Verify in news</a>")
        .append("</div></div>");

    if (byCity.isEmpty()) {
      html.append("<div class='section'><div class='title'>Attractions</div>")
          .append("<div>No attractions found.</div></div>");
    }

    List<String> orderedCities = new ArrayList<>(byCity.keySet());
    orderedCities.sort(Comparator.comparing(city -> !city.equalsIgnoreCase(activeCity)));
    for (String city : orderedCities) {
      List<Attraction> cityAttractions = byCity.getOrDefault(city, List.of());
      html.append("<div class='section'>")
          .append("<div class='title'>").append(escapeHtml(city)).append("</div>")
          .append("<div class='carousel'>");
      for (Attraction attraction : cityAttractions) {
        String image = attraction.imageUrl != null ? attraction.imageUrl : "";
        html.append("<a class='card' href='").append(externalLinkHref(attraction.link)).append("'>")
            .append("<div class='img'>");
        if (!image.isBlank()) {
          html.append("<img src='").append(escapeHtml(image)).append("'/>");
        } else {
          html.append("No Image");
        }
        html.append("</div>")
            .append("<div class='name'>").append(escapeHtml(attraction.title)).append("</div>")
            .append("<div class='meta'>").append(escapeHtml(city)).append("</div>")
            .append("<div class='link'>Wikipedia</div>")
            .append("</a>");
      }
      html.append("</div></div>");
    }
    html.append("</body></html>");
    return html.toString();
  }

  private static String chooseActiveCity(List<String> cities, String preferredCity) {
    if (preferredCity != null && !preferredCity.isBlank()) {
      for (String city : cities) {
        if (city != null && city.equalsIgnoreCase(preferredCity)) {
          return city;
        }
      }
    }
    if (cities == null || cities.isEmpty()) {
      return "Destination";
    }
    return cities.get(0);
  }

  private static String buildBookingUrl(String city) {
    return "https://www.booking.com/searchresults.html?ss=" + encodePathSegment(city);
  }

  private static String buildGuideSearchUrl(String query) {
    return "https://www.google.com/search?q=" + encodePathSegment(query);
  }

  private static String buildCityGuideHtml(String city) {
    List<Attraction> attractions = fetchAttractions(List.of(city));
    List<Restaurant> restaurants = fetchRestaurants(city);
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset='utf-8'/>")
        .append("<style>")
        .append("body{font-family:Arial,sans-serif;margin:0;background:linear-gradient(180deg,#f4fbf8,#eef5ff);color:#17324d;}")
        .append(".hero{padding:14px 16px;background:#ffffff;border-bottom:1px solid #dbe6f0;}")
        .append(".hero h1{margin:0 0 4px 0;font-size:20px;}")
        .append(".hero p{margin:0;color:#526b83;font-size:12px;}")
        .append(".actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px;}")
        .append(".action{display:inline-block;padding:8px 12px;border-radius:999px;text-decoration:none;font-size:12px;font-weight:bold;}")
        .append(".search{background:#0f766e;color:#fff;}")
        .append(".booking{background:#003b95;color:#fff;}")
        .append(".section{padding:12px 16px 4px 16px;}")
        .append(".section h2{margin:0 0 8px 0;font-size:15px;}")
        .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px;}")
        .append(".card{background:#fff;border:1px solid #dce7f3;border-radius:12px;padding:12px;box-shadow:0 2px 6px rgba(15,23,42,0.06);}")
        .append(".card h3{margin:0 0 6px 0;font-size:14px;}")
        .append(".meta{font-size:12px;color:#5d7186;line-height:1.5;}")
        .append(".link{display:inline-block;margin-top:8px;font-size:12px;color:#1d4ed8;text-decoration:none;font-weight:bold;}")
        .append("</style></head><body>");
    html.append("<div class='hero'>")
        .append("<h1>").append(escapeHtml(city)).append(" City Guide</h1>")
        .append("<p>In-app restaurants and attractions view built from accessible public data sources.</p>")
        .append("<div class='actions'>")
        .append("<a class='action search' href='").append(externalLinkHref(buildGuideSearchUrl(city + " restaurants attractions"))).append("'>Search more spots</a>")
        .append("<a class='action booking' href='").append(externalLinkHref(buildBookingUrl(city))).append("'>Open Booking.com</a>")
        .append("</div></div>");

    html.append("<div class='section'><h2>Restaurants</h2><div class='grid'>");
    if (restaurants.isEmpty()) {
      html.append("<div class='card'><h3>No restaurant data loaded</h3><div class='meta'>Open the external search to inspect nearby dining in ")
          .append(escapeHtml(city)).append(".</div></div>");
    } else {
      for (Restaurant restaurant : restaurants) {
        html.append("<div class='card'><h3>").append(escapeHtml(restaurant.name)).append("</h3>")
            .append("<div class='meta'>Cuisine: ").append(escapeHtml(restaurant.cuisine)).append("</div>")
            .append("<div class='meta'>Area: ").append(escapeHtml(restaurant.area)).append("</div>")
            .append("</div>");
      }
    }
    html.append("</div></div>");

    html.append("<div class='section'><h2>Attractions</h2><div class='grid'>");
    if (attractions.isEmpty()) {
      html.append("<div class='card'><h3>No attractions loaded</h3><div class='meta'>Wikipedia attraction summaries were not available for this city.</div></div>");
    } else {
      for (Attraction attraction : attractions) {
        html.append("<div class='card'><h3>").append(escapeHtml(attraction.title)).append("</h3>")
            .append("<div class='meta'>City: ").append(escapeHtml(attraction.city)).append("</div>")
            .append("<a class='link' href='").append(externalLinkHref(attraction.link)).append("'>Open source</a>")
            .append("</div>");
      }
    }
    html.append("</div></div></body></html>");
    return html.toString();
  }

  private static String buildBookingHubHtml(String city) {
    List<HotelOption> hotels = fetchHotels(city);
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset='utf-8'/>")
        .append("<style>")
        .append("body{font-family:Arial,sans-serif;margin:0;background:linear-gradient(180deg,#eff6ff,#f8fafc);color:#17324d;}")
        .append(".hero{padding:16px;background:#fff;border-bottom:1px solid #dce7f3;}")
        .append(".hero h1{margin:0 0 6px 0;font-size:20px;}")
        .append(".hero p{margin:0;color:#5d7186;font-size:12px;line-height:1.5;}")
        .append(".actions{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px;}")
        .append(".action{display:inline-block;padding:8px 12px;border-radius:999px;text-decoration:none;font-size:12px;font-weight:bold;}")
        .append(".booking{background:#003b95;color:#fff;}")
        .append(".search{background:#0f766e;color:#fff;}")
        .append(".section{padding:12px 16px;}")
        .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:10px;}")
        .append(".card{background:#fff;border:1px solid #dce7f3;border-radius:12px;padding:12px;box-shadow:0 2px 8px rgba(15,23,42,0.05);}")
        .append(".card h3{margin:0 0 6px 0;font-size:14px;}")
        .append(".meta{font-size:12px;color:#5d7186;line-height:1.5;}")
        .append("</style></head><body>");
    html.append("<div class='hero'><h1>").append(escapeHtml(city)).append(" Booking Hub</h1>")
        .append("<p>Open Booking.com in your browser, complete the reservation, then sync the exact vendor, rate, fees, and confirmation back into the selected travel leg.</p>")
        .append("<div class='actions'>")
        .append("<a class='action booking' href='").append(externalLinkHref(buildBookingUrl(city))).append("'>Open Booking.com</a>")
        .append("<a class='action search' href='").append(externalLinkHref(buildGuideSearchUrl(city + " hotels"))).append("'>Search hotels</a>")
        .append("</div></div>");
    html.append("<div class='section'><div class='grid'>");
    if (hotels.isEmpty()) {
      html.append("<div class='card'><h3>No local hotel feed loaded</h3><div class='meta'>Use the Booking.com button above and sync the final booked amount in the Booking Sync panel.</div></div>");
    } else {
      for (HotelOption hotel : hotels) {
        html.append("<div class='card'><h3>").append(escapeHtml(hotel.name)).append("</h3>")
            .append("<div class='meta'>Type: ").append(escapeHtml(hotel.type)).append("</div>")
            .append("<div class='meta'>Area: ").append(escapeHtml(hotel.area)).append("</div>")
            .append("</div>");
      }
    }
    html.append("</div></div></body></html>");
    return html.toString();
  }

  private static List<Restaurant> fetchRestaurants(String city) {
    List<Restaurant> cached = RESTAURANT_CACHE.get(city);
    if (cached != null) {
      return cached;
    }
    double[] latLon = geocode(city);
    if (latLon == null) {
      return List.of();
    }
    List<Restaurant> restaurants = new ArrayList<>();
    try {
      String query = "[out:json][timeout:12];("
          + "node[\"amenity\"=\"restaurant\"](around:4000," + latLon[0] + "," + latLon[1] + ");"
          + "way[\"amenity\"=\"restaurant\"](around:4000," + latLon[0] + "," + latLon[1] + ");"
          + "relation[\"amenity\"=\"restaurant\"](around:4000," + latLon[0] + "," + latLon[1] + ");"
          + ");out center 12;";
      HttpRequest request = HttpRequest.newBuilder(URI.create("https://overpass-api.de/api/interpreter"))
          .header("Content-Type", "text/plain")
          .timeout(Duration.ofSeconds(20))
          .POST(HttpRequest.BodyPublishers.ofString(query))
          .build();
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        restaurants.addAll(parseRestaurants(response.body()));
      }
    } catch (Exception e) {
      // Fall back to an empty in-app list and rely on the external search button.
    }
    RESTAURANT_CACHE.put(city, restaurants);
    return restaurants;
  }

  private static List<HotelOption> fetchHotels(String city) {
    List<HotelOption> cached = HOTEL_CACHE.get(city);
    if (cached != null) {
      return cached;
    }
    double[] latLon = geocode(city);
    if (latLon == null) {
      return List.of();
    }
    List<HotelOption> hotels = new ArrayList<>();
    try {
      String query = "[out:json][timeout:12];("
          + "node[\"tourism\"=\"hotel\"](around:5000," + latLon[0] + "," + latLon[1] + ");"
          + "way[\"tourism\"=\"hotel\"](around:5000," + latLon[0] + "," + latLon[1] + ");"
          + "node[\"tourism\"=\"guest_house\"](around:5000," + latLon[0] + "," + latLon[1] + ");"
          + ");out center 12;";
      HttpRequest request = HttpRequest.newBuilder(URI.create("https://overpass-api.de/api/interpreter"))
          .header("Content-Type", "text/plain")
          .timeout(Duration.ofSeconds(20))
          .POST(HttpRequest.BodyPublishers.ofString(query))
          .build();
      HttpClient client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(10))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        hotels.addAll(parseHotels(response.body()));
      }
    } catch (Exception e) {
      return List.of();
    }
    HOTEL_CACHE.put(city, hotels);
    return hotels;
  }

  private static List<HotelOption> parseHotels(String body) {
    if (body == null || body.isBlank()) {
      return List.of();
    }
    List<HotelOption> hotels = new ArrayList<>();
    Pattern pattern = Pattern.compile("\"tags\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
    Matcher matcher = pattern.matcher(body);
    while (matcher.find() && hotels.size() < 8) {
      String tags = matcher.group(1);
      String name = matchField(tags, "\"name\"\\s*:\\s*\"(.*?)\"");
      if (name == null || name.isBlank()) {
        continue;
      }
      String type = matchField(tags, "\"tourism\"\\s*:\\s*\"(.*?)\"");
      String area = matchField(tags, "\"addr:street\"\\s*:\\s*\"(.*?)\"");
      if (area == null || area.isBlank()) {
        area = matchField(tags, "\"addr:suburb\"\\s*:\\s*\"(.*?)\"");
      }
      hotels.add(new HotelOption(name,
          type == null || type.isBlank() ? "Hotel" : type.replace('_', ' '),
          area == null || area.isBlank() ? "Central area" : area));
    }
    return hotels;
  }

  private static List<Restaurant> parseRestaurants(String body) {
    if (body == null || body.isBlank()) {
      return List.of();
    }
    List<Restaurant> restaurants = new ArrayList<>();
    Pattern pattern = Pattern.compile("\"tags\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
    Matcher matcher = pattern.matcher(body);
    while (matcher.find() && restaurants.size() < 8) {
      String tags = matcher.group(1);
      String name = matchField(tags, "\"name\"\\s*:\\s*\"(.*?)\"");
      if (name == null || name.isBlank()) {
        continue;
      }
      String cuisine = matchField(tags, "\"cuisine\"\\s*:\\s*\"(.*?)\"");
      String area = matchField(tags, "\"addr:suburb\"\\s*:\\s*\"(.*?)\"");
      if (area == null || area.isBlank()) {
        area = matchField(tags, "\"addr:street\"\\s*:\\s*\"(.*?)\"");
      }
      restaurants.add(new Restaurant(name,
          cuisine == null || cuisine.isBlank() ? "Local dining" : cuisine.replace(";", ", "),
          area == null || area.isBlank() ? "City center area" : area));
    }
    return restaurants;
  }

  private static String buildCityNewsUrl(String city) {
    return "https://news.google.com/search?q=" + encodePathSegment(city + " travel alerts");
  }

  private static String externalLinkHref(String url) {
    return "app-external:" + encodePathSegment(url);
  }

  private static void installExternalLinkHandler(WebView webView, Runnable onReturn) {
    WebEngine engine = webView.getEngine();
    engine.locationProperty().addListener((obs, oldLocation, newLocation) -> {
      if (newLocation == null || !newLocation.startsWith("app-external:")) {
        return;
      }
      openExternalLink(newLocation.substring("app-external:".length()));
      Platform.runLater(onReturn);
    });
  }

  private static void openExternalLink(String encodedUrl) {
    try {
      String decoded = URLDecoder.decode(encodedUrl, StandardCharsets.UTF_8);
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI.create(decoded));
      }
    } catch (Exception e) {
      // Ignore browser launch failures and keep the app responsive.
    }
  }

  private static String buildAiCardHtml(RouteLegRow row) {
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset='utf-8'/>")
        .append("<style>")
        .append("body{font-family:Arial,sans-serif;margin:0;background:linear-gradient(180deg,#0f172a,#18283d);color:#eff6ff;}")
        .append(".card{padding:14px 16px;}")
        .append(".title{font-size:18px;font-weight:bold;margin:0 0 4px 0;}")
        .append(".subtitle{font-size:12px;color:#b9c9de;margin:0 0 12px 0;}")
        .append(".chips{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px;}")
        .append(".chip{padding:6px 10px;border-radius:999px;background:#1e3a5f;color:#dbeafe;font-size:11px;font-weight:bold;}")
        .append(".grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;}")
        .append(".pane{background:rgba(255,255,255,0.08);border:1px solid rgba(191,219,254,0.18);border-radius:12px;padding:10px 12px;}")
        .append(".pane strong{display:block;font-size:12px;margin-bottom:4px;color:#bfdbfe;}")
        .append(".pane span{font-size:12px;line-height:1.45;display:block;}")
        .append(".takeaways{margin-top:12px;background:#fff;color:#17324d;border-radius:12px;padding:12px;}")
        .append(".takeaways h2{margin:0 0 8px 0;font-size:14px;}")
        .append(".takeaways ul{margin:0;padding-left:18px;}")
        .append(".takeaways li{margin:0 0 6px 0;font-size:12px;}")
        .append(".sources{margin-top:12px;display:flex;flex-wrap:wrap;gap:8px;}")
        .append(".source{display:inline-block;padding:8px 10px;border-radius:999px;background:#f8fafc;color:#17324d;text-decoration:none;font-size:12px;font-weight:bold;}")
        .append(".empty{padding:18px;color:#dbeafe;font-size:12px;}")
        .append("</style></head><body>");
    if (row == null) {
      html.append("<div class='empty'>Select a route leg to see AI takeaways, verification links, and live weather context.</div>");
      html.append("</body></html>");
      return html.toString();
    }

    Map<String, String> fields = parseAlertFields(row.getAiSuggestion());
    List<String> takeaways = buildAlertTakeaways(row, fields);
    html.append("<div class='card'>")
        .append("<div class='title'>").append(escapeHtml(row.getFrom())).append(" to ")
        .append(escapeHtml(row.getTo())).append("</div>")
        .append("<div class='subtitle'>Smart route card for ").append(escapeHtml(row.getMode()))
        .append(" with AI summary, live weather context, and verification links.</div>")
        .append("<div class='chips'>")
        .append("<div class='chip'>").append(escapeHtml(row.getMode())).append("</div>")
        .append("<div class='chip'>").append(escapeHtml(formatCurrency(row.getCost()))).append("</div>")
        .append("<div class='chip'>").append(escapeHtml(formatDuration(row.getHours()))).append("</div>")
        .append("</div>")
        .append("<div class='grid'>")
        .append(buildInfoPane("🌤️ Weather", fields.get("Weather expected")))
        .append(buildInfoPane("🚦 Delays", fields.get("Delays with travel")))
        .append(buildInfoPane("🎭 Events", fields.get("Events in the city")))
        .append(buildInfoPane("🩺 Health", fields.get("Health warnings")))
        .append(buildInfoPane("🌫️ Air Quality", fields.get("AQI and hazard information")))
        .append(buildInfoPane("🧠 User prompt", row.getQuestion() == null || row.getQuestion().isBlank()
            ? "Using the default route-alert prompt."
            : row.getQuestion()))
        .append("</div>")
        .append("<div class='takeaways'><h2>Key Takeaways</h2><ul>");
    for (String takeaway : takeaways) {
      html.append("<li>").append(escapeHtml(takeaway)).append("</li>");
    }
    html.append("</ul></div>")
        .append("<div class='sources'>")
        .append(buildSourceChip("News", buildRouteNewsUrl(row)))
        .append(buildSourceChip("Travel delays", buildTravelDelayUrl(row)))
        .append(buildSourceChip("Weather.com", buildWeatherDotComUrl(row)))
        .append(buildSourceChip("Open-Meteo", buildOpenMeteoVerifyUrl(row)))
        .append("</div></div></body></html>");
    return html.toString();
  }

  private static String buildInfoPane(String title, String value) {
    String safeValue = value == null || value.isBlank() ? "Unknown" : value;
    return "<div class='pane'><strong>" + escapeHtml(title) + "</strong><span>" + escapeHtml(safeValue) + "</span></div>";
  }

  private static String buildSourceChip(String label, String url) {
    return "<a class='source' href='" + externalLinkHref(url) + "'>" + escapeHtml(label) + "</a>";
  }

  private static String renderAiCardHtml(RouteLegRow row) {
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset='utf-8'/>")
        .append("<style>")
        .append("body{font-family:Arial,sans-serif;margin:0;background:linear-gradient(180deg,#0f172a,#18283d);color:#eff6ff;}")
        .append(".card{padding:14px 16px;}")
        .append(".title{font-size:18px;font-weight:bold;margin:0 0 4px 0;}")
        .append(".subtitle{font-size:12px;color:#b9c9de;margin:0 0 12px 0;}")
        .append(".chips{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px;}")
        .append(".chip{padding:6px 10px;border-radius:999px;background:#1e3a5f;color:#dbeafe;font-size:11px;font-weight:bold;}")
        .append(".grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;}")
        .append(".pane{background:rgba(255,255,255,0.08);border:1px solid rgba(191,219,254,0.18);border-radius:12px;padding:10px 12px;}")
        .append(".pane strong{display:block;font-size:12px;margin-bottom:4px;color:#bfdbfe;}")
        .append(".pane span{font-size:12px;line-height:1.45;display:block;}")
        .append(".takeaways{margin-top:12px;background:#fff;color:#17324d;border-radius:12px;padding:12px;}")
        .append(".takeaways h2{margin:0 0 8px 0;font-size:14px;}")
        .append(".takeaways ul{margin:0;padding-left:18px;}")
        .append(".takeaways li{margin:0 0 6px 0;font-size:12px;}")
        .append(".sources{margin-top:12px;display:flex;flex-wrap:wrap;gap:8px;}")
        .append(".source{display:inline-block;padding:8px 10px;border-radius:999px;background:#f8fafc;color:#17324d;text-decoration:none;font-size:12px;font-weight:bold;}")
        .append(".empty{padding:18px;color:#dbeafe;font-size:12px;}")
        .append("</style></head><body>");
    if (row == null) {
      html.append("<div class='empty'>Select a route leg to see AI takeaways, verification links, and live weather context.</div>");
      html.append("</body></html>");
      return html.toString();
    }

    Map<String, String> fields = parseAlertFields(row.getAiSuggestion());
    List<String> takeaways = buildAlertTakeaways(row, fields);
    html.append("<div class='card'>")
        .append("<div class='title'>").append(escapeHtml(row.getFrom())).append(" to ")
        .append(escapeHtml(row.getTo())).append("</div>")
        .append("<div class='subtitle'>Smart route card for ").append(escapeHtml(row.getMode()))
        .append(" with AI summary, live weather context, and verification links.</div>")
        .append("<div class='chips'>")
        .append("<div class='chip'>").append(escapeHtml(row.getMode())).append("</div>")
        .append("<div class='chip'>").append(escapeHtml(formatCurrency(row.getCost()))).append("</div>")
        .append("<div class='chip'>").append(escapeHtml(formatDuration(row.getHours()))).append("</div>")
        .append("</div>")
        .append("<div class='grid'>")
        .append(buildInfoPane("Weather", fields.get("Weather expected")))
        .append(buildInfoPane("Delays", fields.get("Delays with travel")))
        .append(buildInfoPane("Events", fields.get("Events in the city")))
        .append(buildInfoPane("Health", fields.get("Health warnings")))
        .append(buildInfoPane("Air Quality", fields.get("AQI and hazard information")))
        .append(buildInfoPane("User Prompt", row.getQuestion() == null || row.getQuestion().isBlank()
            ? "Using the default route-alert prompt."
            : row.getQuestion()))
        .append("</div>")
        .append("<div class='takeaways'><h2>Key Takeaways</h2><ul>");
    for (String takeaway : takeaways) {
      html.append("<li>").append(escapeHtml(takeaway)).append("</li>");
    }
    html.append("</ul></div>")
        .append("<div class='sources'>")
        .append(buildSourceChip("AI source: " + detectAlertSourceLabel(row.getAiSuggestion()), buildRouteNewsUrl(row)))
        .append(buildSourceChip("News", buildRouteNewsUrl(row)))
        .append(buildSourceChip("Travel delays", buildTravelDelayUrl(row)))
        .append(buildSourceChip("Weather.com", buildWeatherDotComUrl(row)))
        .append(buildSourceChip("Open-Meteo", buildOpenMeteoVerifyUrl(row)))
        .append("</div></div></body></html>");
    return html.toString();
  }

  private static String detectAlertSourceLabel(String alertText) {
    if (alertText == null || alertText.isBlank()) {
      return "Loading";
    }
    if (alertText.contains("[Website fallback]")) {
      return "Website fallback";
    }
    Map<String, String> fields = parseAlertFields(alertText);
    if (allAlertFieldsUnknown(fields)) {
      return "Loading";
    }
    return "OpenAI";
  }

  private static boolean allAlertFieldsUnknown(Map<String, String> fields) {
    for (String value : fields.values()) {
      if (value != null && !value.isBlank() && !"Unknown".equalsIgnoreCase(value.trim())) {
        return false;
      }
    }
    return true;
  }

  private static Map<String, String> parseAlertFields(String response) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("Weather expected", "Unknown");
    fields.put("Delays with travel", "Unknown");
    fields.put("Events in the city", "Unknown");
    fields.put("Health warnings", "Unknown");
    fields.put("AQI and hazard information", "Unknown");
    if (response == null || response.isBlank()) {
      return fields;
    }
    String normalizedResponse = response.replace("[Website fallback]", "").trim();
    if (normalizedResponse.contains("{") && normalizedResponse.contains("}")) {
      String jsonLike = normalizedResponse.substring(normalizedResponse.indexOf('{'),
          normalizedResponse.lastIndexOf('}') + 1);
      putIfPresent(fields, "Weather expected", extractJsonValue(jsonLike, "weather"));
      putIfPresent(fields, "Delays with travel", extractJsonValue(jsonLike, "delays"));
      putIfPresent(fields, "Events in the city", extractJsonValue(jsonLike, "events"));
      putIfPresent(fields, "Health warnings", extractJsonValue(jsonLike, "health"));
      putIfPresent(fields, "AQI and hazard information", extractJsonValue(jsonLike, "aqi"));
    }
    String[] segments = normalizedResponse.split("\\|");
    for (String segment : segments) {
      String trimmed = segment.trim();
      int colon = trimmed.indexOf(':');
      if (colon < 0) {
        continue;
      }
      String key = trimmed.substring(0, colon).trim();
      String value = trimmed.substring(colon + 1).trim();
      if (fields.containsKey(key) && !value.isBlank()) {
        fields.put(key, value);
      }
    }
    return fields;
  }

  private static void putIfPresent(Map<String, String> fields, String key, String value) {
    if (value != null && !value.isBlank()) {
      fields.put(key, value.trim());
    }
  }

  private static String extractJsonValue(String body, String key) {
    return matchField(body, "\"" + Pattern.quote(key) + "\"\\s*:\\s*\"(.*?)\"");
  }

  private static List<String> buildAlertTakeaways(RouteLegRow row, Map<String, String> fields) {
    List<String> takeaways = new ArrayList<>();
    addTakeaway(takeaways, fields.get("Weather expected"), "Weather watch: ");
    addTakeaway(takeaways, fields.get("Delays with travel"), "Travel friction: ");
    addTakeaway(takeaways, fields.get("Events in the city"), "City pulse: ");
    addTakeaway(takeaways, fields.get("Health warnings"), "Health note: ");
    addTakeaway(takeaways, fields.get("AQI and hazard information"), "Air quality note: ");
    if (takeaways.isEmpty()) {
      takeaways.add("AI details are still loading. Use the verification links below while the live summary refreshes.");
    }
    if (row.getTravelDate() != null) {
      String travelTime = row.getTravelTime();
      if (travelTime == null || travelTime.isBlank()) {
        takeaways.add("Scheduled travel date: " + row.getTravelDate() + ".");
      } else {
        takeaways.add("Scheduled travel slot: " + row.getTravelDate() + " at " + travelTime + ".");
      }
    }
    return takeaways.size() > 4 ? takeaways.subList(0, 4) : takeaways;
  }

  private static void addTakeaway(List<String> takeaways, String value, String prefix) {
    if (value == null || value.isBlank()) {
      return;
    }
    String normalized = value.trim();
    if ("Unknown".equalsIgnoreCase(normalized) || normalized.startsWith("No live")) {
      return;
    }
    takeaways.add(prefix + normalized);
  }

  private static String buildRouteNewsUrl(RouteLegRow row) {
    return "https://news.google.com/search?q=" + encodePathSegment(
        row.getFrom() + " " + row.getTo() + " " + row.getMode() + " travel alerts");
  }

  private static String buildTravelDelayUrl(RouteLegRow row) {
    return "https://www.bing.com/news/search?q=" + encodePathSegment(
        row.getTo() + " " + row.getMode() + " travel delay");
  }

  private static String buildWeatherDotComUrl(RouteLegRow row) {
    return "https://www.google.com/search?q=" + encodePathSegment("site:weather.com " + row.getTo() + " weather");
  }

  private static String buildOpenMeteoVerifyUrl(RouteLegRow row) {
    if (row == null) {
      return "https://open-meteo.com/";
    }
    return "https://api.open-meteo.com/v1/forecast?latitude=" + row.getToLat() + "&longitude=" + row.getToLon()
        + "&current=temperature_2m,weather_code,wind_speed_10m&temperature_unit=fahrenheit";
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
    TableColumn<RouteLegRow, Double> costCol = new TableColumn<>("Booked Total");
    costCol.setCellValueFactory(new PropertyValueFactory<>("cost"));
    costCol.setCellFactory(col -> new TableCell<>() {
      @Override
      protected void updateItem(Double item, boolean empty) {
        super.updateItem(item, empty);
        setText(empty || item == null ? "" : formatCurrency(item));
      }
    });
    costCol.setPrefWidth(110);
    TableColumn<RouteLegRow, String> estimateCol = new TableColumn<>("Estimate");
    estimateCol.setCellValueFactory(new PropertyValueFactory<>("estimateCostDisplay"));
    estimateCol.setPrefWidth(100);
    TableColumn<RouteLegRow, String> varianceCol = new TableColumn<>("Variance");
    varianceCol.setCellValueFactory(new PropertyValueFactory<>("costVarianceDisplay"));
    varianceCol.setPrefWidth(95);
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

    TableColumn<RouteLegRow, String> bookingStatusCol = new TableColumn<>("Booking Status");
    bookingStatusCol.setCellValueFactory(new PropertyValueFactory<>("bookingStatus"));
    bookingStatusCol.setPrefWidth(130);

    TableColumn<RouteLegRow, String> bookingVendorCol = new TableColumn<>("Vendor");
    bookingVendorCol.setCellValueFactory(new PropertyValueFactory<>("bookingVendor"));
    bookingVendorCol.setPrefWidth(140);

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

    TableColumn<RouteLegRow, String> bookingNotesCol = new TableColumn<>("Booking Notes");
    bookingNotesCol.setCellValueFactory(new PropertyValueFactory<>("bookingNotes"));
    bookingNotesCol.setPrefWidth(220);
    bookingNotesCol.setCellFactory(col -> new WrappingTableCell());

    table.getColumns().add(fromCol);
    table.getColumns().add(toCol);
    table.getColumns().add(modeCol);
    table.getColumns().add(costCol);
    table.getColumns().add(estimateCol);
    table.getColumns().add(varianceCol);
    table.getColumns().add(timeCol);
    table.getColumns().add(travelDateCol);
    table.getColumns().add(travelTimeCol);
    table.getColumns().add(bookingStatusCol);
    table.getColumns().add(bookingVendorCol);
    table.getColumns().add(bookingRefCol);
    table.getColumns().add(receiptActionCol);
    table.getColumns().add(receiptIdCol);
    table.getColumns().add(bookingNotesCol);
    table.getColumns().add(expenseSummaryCol);

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

  private static final class Restaurant {
    private final String name;
    private final String cuisine;
    private final String area;

    private Restaurant(String name, String cuisine, String area) {
      this.name = name;
      this.cuisine = cuisine;
      this.area = area;
    }
  }

  private static final class HotelOption {
    private final String name;
    private final String type;
    private final String area;

    private HotelOption(String name, String type, String area) {
      this.name = name;
      this.type = type;
      this.area = area;
    }
  }

  private static final class BusinessTripProfile {
    private String meetingGoals = "";
    private String stakeholderPlan = "";
    private String logisticsPlan = "";
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

  private static final class OpenAiCallResult {
    private final boolean success;
    private final boolean rateLimited;
    private final String text;
    private final String errorMessage;

    private OpenAiCallResult(boolean success, boolean rateLimited, String text, String errorMessage) {
      this.success = success;
      this.rateLimited = rateLimited;
      this.text = text;
      this.errorMessage = errorMessage;
    }

    private static OpenAiCallResult success(String text) {
      return new OpenAiCallResult(true, false, text, null);
    }

    private static OpenAiCallResult failure(String errorMessage) {
      return new OpenAiCallResult(false, false, null, errorMessage);
    }

    private static OpenAiCallResult rateLimited(String errorMessage) {
      return new OpenAiCallResult(false, true, null, errorMessage);
    }
  }

  public static final class RouteLegRow {
    private final String from;
    private final String to;
    private final String mode;
    private final double estimateCost;
    private final double hours;
    private final double toLat;
    private final double toLon;
    private String question;
    private String aiSuggestion;
    private String bookingStatus;
    private String bookingReference;
    private String bookingVendor;
    private double bookedBaseCost;
    private double bookedFees;
    private String bookingNotes;
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
      this.estimateCost = cost;
      this.hours = hours;
      this.toLat = toLat;
      this.toLon = toLon;
      this.question = question;
      this.aiSuggestion = aiSuggestion;
      this.bookingStatus = "Estimate only";
      this.bookingReference = "";
      this.bookingVendor = "";
      this.bookedBaseCost = 0;
      this.bookedFees = 0;
      this.bookingNotes = "";
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
      return getBookedTotal() > 0 ? getBookedTotal() : estimateCost;
    }

    public double getEstimateCost() {
      return estimateCost;
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

    public String getBookingVendor() {
      return bookingVendor;
    }

    public double getBookedBaseCost() {
      return bookedBaseCost;
    }

    public double getBookedFees() {
      return bookedFees;
    }

    public double getBookedTotal() {
      return bookedBaseCost + bookedFees;
    }

    public String getBookedTotalDisplay() {
      return getBookedTotal() > 0 ? formatCurrency(getBookedTotal()) : "";
    }

    public String getEstimateCostDisplay() {
      return formatCurrency(estimateCost);
    }

    public String getCostVarianceDisplay() {
      if (getBookedTotal() <= 0) {
        return "";
      }
      double variance = getBookedTotal() - estimateCost;
      String prefix = variance > 0 ? "+" : "";
      return prefix + formatCurrency(variance);
    }

    public String getBookingNotes() {
      return bookingNotes;
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

    public void setBookingVendor(String bookingVendor) {
      this.bookingVendor = bookingVendor;
    }

    public void setBookedBaseCost(double bookedBaseCost) {
      this.bookedBaseCost = bookedBaseCost;
    }

    public void setBookedFees(double bookedFees) {
      this.bookedFees = bookedFees;
    }

    public void setBookingNotes(String bookingNotes) {
      this.bookingNotes = bookingNotes;
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
      return getBookedTotal() > 0 || (bookingReference != null && !bookingReference.isBlank());
    }

    public boolean hasReceipt() {
      return receiptId != null && !receiptId.isBlank();
    }

    public void book(String reference) {
      bookingStatus = "Booked";
      bookingReference = reference;
    }

    public void syncBooking(String vendor, double baseCost, double fees, String reference, String notes) {
      bookingVendor = vendor == null ? "" : vendor.trim();
      bookedBaseCost = Math.max(0, baseCost);
      bookedFees = Math.max(0, fees);
      bookingReference = reference == null ? "" : reference.trim();
      bookingNotes = notes == null ? "" : notes.trim();
      bookingStatus = getBookedTotal() > 0 ? "Synced from booking" : "Estimate only";
      if (bookingReference.isBlank() && getBookedTotal() > 0) {
        bookingReference = generateBookingReference(this);
      }
    }

    public void clearBookingSync() {
      bookingVendor = "";
      bookedBaseCost = 0;
      bookedFees = 0;
      bookingReference = "";
      bookingNotes = "";
      bookingStatus = "Estimate only";
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

  private static void populateBookingSyncFields(RouteLegRow row, Label context, TextField vendorField,
      TextField baseCostField, TextField feesField, TextField referenceField, TextArea notesArea) {
    if (context == null || vendorField == null || baseCostField == null || feesField == null
        || referenceField == null || notesArea == null) {
      return;
    }
    if (row == null) {
      context.setText("Selected leg: None");
      vendorField.setText("");
      baseCostField.setText("");
      feesField.setText("");
      referenceField.setText("");
      notesArea.setText("");
      return;
    }
    context.setText("Selected leg: " + row.getFrom() + " -> " + row.getTo() + " (" + row.getMode() + ")");
    vendorField.setText(row.getBookingVendor());
    baseCostField.setText(row.getBookedBaseCost() > 0 ? formatNumber(Double.toString(row.getBookedBaseCost())) : "");
    feesField.setText(row.getBookedFees() > 0 ? formatNumber(Double.toString(row.getBookedFees())) : "");
    referenceField.setText(row.getBookingReference());
    notesArea.setText(row.getBookingNotes());
  }

  private static void openSelectedBookingLink(TableView<RouteLegRow> table) {
    if (table == null) {
      return;
    }
    RouteLegRow row = table.getSelectionModel().getSelectedItem();
    if (row == null) {
      return;
    }
    openExternalLink(encodePathSegment(buildBookingUrl(row.getTo())));
  }

  private static void saveBookingSync(TableView<RouteLegRow> table, TextField vendorField, TextField baseCostField,
      TextField feesField, TextField referenceField, TextArea notesArea, TextArea routeBriefArea) {
    if (table == null) {
      return;
    }
    RouteLegRow row = table.getSelectionModel().getSelectedItem();
    if (row == null) {
      return;
    }
    double baseCost = parseCurrencyInput(baseCostField.getText());
    double fees = parseCurrencyInput(feesField.getText());
    row.syncBooking(vendorField.getText(), baseCost, fees, referenceField.getText(), notesArea.getText());
    table.refresh();
    triggerSpendUpdate(table);
    loadTravelBrief(row, routeBriefArea);
  }

  private static void clearBookingSync(TableView<RouteLegRow> table, Label context, TextField vendorField,
      TextField baseCostField, TextField feesField, TextField referenceField, TextArea notesArea,
      TextArea routeBriefArea) {
    if (table == null) {
      return;
    }
    RouteLegRow row = table.getSelectionModel().getSelectedItem();
    if (row == null) {
      populateBookingSyncFields(null, context, vendorField, baseCostField, feesField, referenceField, notesArea);
      return;
    }
    row.clearBookingSync();
    table.refresh();
    triggerSpendUpdate(table);
    populateBookingSyncFields(row, context, vendorField, baseCostField, feesField, referenceField, notesArea);
    loadTravelBrief(row, routeBriefArea);
  }

  private static double parseCurrencyInput(String value) {
    if (value == null || value.isBlank()) {
      return 0;
    }
    try {
      return Double.parseDouble(value.replace("$", "").replace(",", "").trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static void loadTravelBrief(RouteLegRow row, TextArea briefArea) {
    if (briefArea == null) {
      return;
    }
    if (row == null) {
      briefArea.setText("Select a route leg to see live weather, city context, and booking guidance.");
      return;
    }
    briefArea.setText("Loading travel brief...");
    Thread worker = new Thread(() -> {
      LiveAlertData liveAlert = getLiveAlertData(row);
      String citySummary = buildWikiSuggestion(row.getTo());
      StringBuilder brief = new StringBuilder();
      brief.append("Mode: ").append(row.getMode()).append("\n");
      brief.append("Estimated travel cost: ").append(formatCurrency(row.getEstimateCost())).append("\n");
      brief.append("Booked total in table: ").append(formatCurrency(row.getCost())).append("\n");
      brief.append("Weather: ").append(liveAlert != null ? liveAlert.weather : "Unknown").append("\n");
      brief.append("Air quality: ").append(liveAlert != null ? liveAlert.aqi : "Unknown").append("\n");
      brief.append("City context: ").append(citySummary == null || citySummary.isBlank() ? "Unavailable." : citySummary).append("\n");
      if (row.isBooked()) {
        brief.append("Synced booking vendor: ").append(row.getBookingVendor().isBlank() ? "Not provided" : row.getBookingVendor()).append("\n");
        brief.append("Booking reference: ").append(row.getBookingReference().isBlank() ? "Not provided" : row.getBookingReference()).append("\n");
      } else {
        brief.append("Booking sync: Open Booking.com, then enter the exact vendor, base rate, and fees here.\n");
      }
      Platform.runLater(() -> briefArea.setText(brief.toString()));
    }, "travel-brief-worker");
    worker.setDaemon(true);
    worker.start();
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
    String prompt = buildStructuredAlertPrompt(from, to, "travel", null, null);
    try {
      for (int attempt = 0; attempt < 2; attempt++) {
        OpenAiCallResult result = requestOpenAiText(apiKey, model, prompt);
        if (result.rateLimited) {
          aiRateLimitedUntil = System.currentTimeMillis() + AI_RATE_LIMIT_COOLDOWN_MS;
          if (attempt == 0) {
            Thread.sleep(2000);
            continue;
          }
          return buildAlertUnavailable();
        }
        if (!result.success) {
          return buildAlertUnavailable();
        }
        String text = result.text;
        if (text == null || text.isBlank()) {
          return buildAlertUnavailable();
        }
        return normalizeAlertResponse(text.strip(), new RouteLegRow(from, to, "Travel", 0, 0, "", "", 0, 0), null);
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

  private static void askAiForSelection(TableView<RouteLegRow> table, TextField input, WebView response,
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
      return buildAlertFromWebsiteData(row, liveAlert);
    }
    String apiKey = System.getenv("OPENAI_API_KEY");
    if (apiKey == null || apiKey.isBlank()) {
      apiKey = readConfigValue("OPENAI_API_KEY");
      if (apiKey == null || apiKey.isBlank()) {
        return buildAlertFromWebsiteData(row, liveAlert);
      }
    }
    apiKey = apiKey.trim();
    String model = System.getenv("OPENAI_MODEL");
    if (model == null || model.isBlank()) {
      model = readConfigValue("OPENAI_MODEL");
    }
    if (model == null || model.isBlank()) {
      model = "gpt-4o-mini";
    }
    String prompt = buildAlertPrompt(row, question, liveAlert);
    try {
      throttleAiRequests();
      for (int attempt = 0; attempt < 2; attempt++) {
        OpenAiCallResult result = requestOpenAiText(apiKey, model, prompt);
        if (result.rateLimited) {
          aiRateLimitedUntil = System.currentTimeMillis() + AI_RATE_LIMIT_COOLDOWN_MS;
          if (attempt == 0) {
            Thread.sleep(2000);
            continue;
          }
          return buildAlertFromWebsiteData(row, liveAlert);
        }
        if (!result.success) {
          return buildAlertFromWebsiteData(row, liveAlert);
        }
        String text = result.text;
        if (text == null || text.isBlank()) {
          String errorMessage = result.errorMessage;
          if (errorMessage != null && !errorMessage.isBlank()) {
            return buildAlertFromWebsiteData(row, liveAlert);
          }
          return buildAlertFromWebsiteData(row, liveAlert);
        }
        String answer = normalizeAlertResponse(text.strip(), row, liveAlert);
        if (!isAlertFallback(answer)) {
          AI_QA_CACHE.put(cacheKey, answer);
        }
        return answer;
      }
      return buildAlertFromWebsiteData(row, liveAlert);
    } catch (Exception e) {
      return buildAlertFromWebsiteData(row, liveAlert);
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

  private static OpenAiCallResult requestOpenAiText(String apiKey, String model, String prompt) {
    HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    OpenAiCallResult responsesResult = callResponsesApi(client, apiKey, model, prompt);
    if (responsesResult.success || responsesResult.rateLimited) {
      return responsesResult;
    }

    OpenAiCallResult chatResult = callChatCompletionsApi(client, apiKey, model, prompt);
    if (chatResult.success || chatResult.rateLimited) {
      return chatResult;
    }

    if (chatResult.errorMessage != null && !chatResult.errorMessage.isBlank()) {
      return chatResult;
    }
    return responsesResult;
  }

  private static OpenAiCallResult callResponsesApi(HttpClient client, String apiKey, String model, String prompt) {
    String body = "{\"model\":\"" + jsonEscape(model) + "\","
        + "\"input\":[{\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"" + jsonEscape(prompt) + "\"}]}],"
        + "\"temperature\":0.4}";
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
          .header("Authorization", "Bearer " + apiKey)
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(20))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 429) {
        return OpenAiCallResult.rateLimited(extractErrorMessage(response.body()));
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return OpenAiCallResult.failure(extractErrorMessage(response.body()));
      }
      String text = extractResponseText(response.body());
      if (text == null || text.isBlank()) {
        return OpenAiCallResult.failure(extractErrorMessage(response.body()));
      }
      return OpenAiCallResult.success(text.strip());
    } catch (Exception e) {
      return OpenAiCallResult.failure(e.getMessage());
    }
  }

  private static OpenAiCallResult callChatCompletionsApi(HttpClient client, String apiKey, String model, String prompt) {
    String body = "{\"model\":\"" + jsonEscape(model) + "\","
        + "\"messages\":[{\"role\":\"user\",\"content\":\"" + jsonEscape(prompt) + "\"}],"
        + "\"temperature\":0.4}";
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/chat/completions"))
          .header("Authorization", "Bearer " + apiKey)
          .header("Content-Type", "application/json")
          .timeout(Duration.ofSeconds(20))
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 429) {
        return OpenAiCallResult.rateLimited(extractErrorMessage(response.body()));
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        return OpenAiCallResult.failure(extractErrorMessage(response.body()));
      }
      String text = extractChatCompletionText(response.body());
      if (text == null || text.isBlank()) {
        return OpenAiCallResult.failure(extractErrorMessage(response.body()));
      }
      return OpenAiCallResult.success(text.strip());
    } catch (Exception e) {
      return OpenAiCallResult.failure(e.getMessage());
    }
  }

  private static String extractChatCompletionText(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    String content = matchField(body, "\"content\"\\s*:\\s*\"(.*?)\"");
    if (content != null && !content.isBlank()) {
      return content;
    }
    return null;
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
      String value = properties.getProperty(key);
      return value == null ? null : value.trim();
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
        + " | " + (row.getBookingVendor().isBlank() ? row.getBookingStatus() : row.getBookingVendor())
        + " | " + dateLabel + timeLabel;
  }

  private static String buildReportDetail(RouteLegRow row) {
    if (row == null) {
      return "";
    }
    StringBuilder detail = new StringBuilder();
    detail.append("Trip: ").append(row.getFrom()).append(" -> ").append(row.getTo()).append("\n");
    detail.append("Mode: ").append(row.getMode()).append("\n");
    detail.append("Booked total: ").append(formatCurrency(row.getCost())).append("\n");
    detail.append("Estimate: ").append(formatCurrency(row.getEstimateCost())).append("\n");
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
    if (row.getBookingVendor() != null && !row.getBookingVendor().isBlank()) {
      detail.append("Vendor: ").append(row.getBookingVendor()).append("\n");
    }
    if (row.getBookingNotes() != null && !row.getBookingNotes().isBlank()) {
      detail.append("Booking notes: ").append(row.getBookingNotes()).append("\n");
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
      ListView<RouteLegRow> reportList, TextArea reportDetails, Label reportSummary, String reportFilter) {
    if (table == null || chart == null || reportList == null || reportDetails == null || reportSummary == null) {
      return;
    }
    RouteLegRow selected = reportList.getSelectionModel().getSelectedItem();
    List<RouteLegRow> receipts = new ArrayList<>();
    List<RouteLegRow> filteredRows = new ArrayList<>();
    double bookedTotal = 0;
    int pendingReceipts = 0;
    chart.getData().clear();
    for (RouteLegRow row : table.getItems()) {
      if (row == null) {
        continue;
      }
      if (row.isBooked()) {
        bookedTotal += row.getCost();
        if (!row.hasReceipt()) {
          pendingReceipts++;
        }
      }
      if (matchesReportFilter(row, reportFilter)) {
        filteredRows.add(row);
      }
      if (row.hasReceipt()) {
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
            reportList.getSelectionModel().select(receipt);
            reportDetails.setText(detail);
          });
        });
      }
    }
    reportList.getItems().setAll(filteredRows);
    reportSummary.setText("Booked total: " + formatCurrency(bookedTotal)
        + " | Receipts: " + receipts.size()
        + " | Pending receipts: " + pendingReceipts
        + " | View: " + reportFilter);
    if (selected != null && filteredRows.contains(selected)) {
      reportList.getSelectionModel().select(selected);
      reportDetails.setText(buildReportDetail(selected));
    } else {
      reportDetails.setText("");
    }
  }

  private static boolean matchesReportFilter(RouteLegRow row, String reportFilter) {
    String filter = reportFilter == null ? "Receipts" : reportFilter;
    switch (filter) {
      case "Booked Travel":
        return row.isBooked();
      case "Pending Receipts":
        return row.isBooked() && !row.hasReceipt();
      case "All Legs":
        return true;
      case "Receipts":
      default:
        return row.hasReceipt();
    }
  }

  private static void printReportView(List<RouteLegRow> rows, String reportFilter) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    openHtmlInBrowser(buildReportHtml(rows, reportFilter));
  }

  private static void openReportSummary(List<RouteLegRow> rows, String reportFilter) {
    if (rows == null || rows.isEmpty()) {
      return;
    }
    openHtmlInBrowser(buildReportHtml(rows, reportFilter));
  }

  private static void openHtmlInBrowser(String html) {
    if (html == null || html.isBlank()) {
      return;
    }
    try {
      Path tempFile = Files.createTempFile("travel-report-", ".html");
      Files.writeString(tempFile, html, StandardCharsets.UTF_8);
      if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(tempFile.toUri());
      }
    } catch (Exception e) {
      // Ignore browser export failures.
    }
  }

  private static String buildReportHtml(List<RouteLegRow> rows, String reportFilter) {
    double total = 0;
    for (RouteLegRow row : rows) {
      total += row.getCost();
    }
    StringBuilder html = new StringBuilder();
    html.append("<!doctype html><html><head><meta charset='utf-8'/>")
        .append("<style>")
        .append("body{font-family:Arial,sans-serif;margin:24px;background:#f8fafc;color:#17324d;}")
        .append("h1{margin:0 0 8px 0;} .summary{margin-bottom:16px;color:#526b83;}")
        .append("table{width:100%;border-collapse:collapse;background:#fff;}")
        .append("th,td{border:1px solid #dce7f3;padding:8px 10px;font-size:12px;text-align:left;vertical-align:top;}")
        .append("th{background:#eff6ff;} .small{color:#5d7186;font-size:11px;}")
        .append("</style></head><body>")
        .append("<h1>Travel Report</h1>")
        .append("<div class='summary'>View: ").append(escapeHtml(reportFilter == null ? "Receipts" : reportFilter))
        .append(" | Items: ").append(rows.size())
        .append(" | Total: ").append(escapeHtml(formatCurrency(total))).append("</div>")
        .append("<table><thead><tr><th>Trip</th><th>Mode</th><th>Booked Total</th><th>Estimate</th><th>Vendor</th><th>Reference</th><th>Notes</th></tr></thead><tbody>");
    for (RouteLegRow row : rows) {
      html.append("<tr><td>").append(escapeHtml(row.getFrom() + " -> " + row.getTo())).append("</td>")
          .append("<td>").append(escapeHtml(row.getMode())).append("</td>")
          .append("<td>").append(escapeHtml(formatCurrency(row.getCost()))).append("</td>")
          .append("<td>").append(escapeHtml(formatCurrency(row.getEstimateCost()))).append("</td>")
          .append("<td>").append(escapeHtml(row.getBookingVendor().isBlank() ? row.getBookingStatus() : row.getBookingVendor())).append("</td>")
          .append("<td>").append(escapeHtml(row.getBookingReference())).append("</td>")
          .append("<td>").append(escapeHtml(row.getBookingNotes())).append("</td></tr>");
    }
    html.append("</tbody></table><p class='small'>Use the browser print dialog for a hard copy or PDF.</p></body></html>");
    return html.toString();
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

  private static void updateAiPanel(RouteLegRow row, TextField questionField, WebView responseView, Label context) {
    if (row == null) {
      context.setText("Selected leg: None");
      questionField.setText("");
      responseView.getEngine().loadContent(renderAiCardHtml(null));
      return;
    }
    context.setText("Selected leg: " + row.getFrom() + " -> " + row.getTo() + " (" + row.getMode() + ")");
    String question = row.getQuestion();
    questionField.setText(question == null ? "" : question);
    responseView.getEngine().loadContent(renderAiCardHtml(row));
  }

  private static void populateLiveAlerts(TableView<RouteLegRow> table, Runnable onUpdate) {
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
        Platform.runLater(() -> {
          table.refresh();
          if (onUpdate != null) {
            onUpdate.run();
          }
        });
      }
    }, "live-alerts-worker");
    worker.setDaemon(true);
    worker.start();
  }

  private static void refreshAgendaCities(ListView<String> routeList, ComboBox<String> citySelect,
      ListView<String> agendaList) {
    String current = citySelect.getValue();
    citySelect.getItems().setAll(routeList.getItems());
    if (current != null && citySelect.getItems().contains(current)) {
      citySelect.setValue(current);
    } else if (!citySelect.getItems().isEmpty()) {
      citySelect.getSelectionModel().selectFirst();
    } else {
      citySelect.setValue(null);
    }
    updateAgendaForCity(citySelect, agendaList);
  }

  private static void updateAgendaForCity(ComboBox<String> citySelect, ListView<String> agendaList) {
    String city = citySelect.getValue();
    if (city == null || city.isBlank()) {
      agendaList.setItems(FXCollections.observableArrayList());
      return;
    }
    agendaList.setItems(agendaItemsForCity(city));
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

  private static BusinessTripProfile businessTripProfileForCity(String city) {
    if (city == null || city.isBlank()) {
      return new BusinessTripProfile();
    }
    String key = normalizePlaceKey(city);
    synchronized (BUSINESS_TRIP_PROFILES) {
      BusinessTripProfile profile = BUSINESS_TRIP_PROFILES.get(key);
      if (profile == null) {
        profile = new BusinessTripProfile();
        BUSINESS_TRIP_PROFILES.put(key, profile);
      }
      return profile;
    }
  }

  private static void loadBusinessTripProfile(String city, TextArea goalsArea, TextArea stakeholderArea,
      TextArea logisticsArea) {
    if (goalsArea == null || stakeholderArea == null || logisticsArea == null) {
      return;
    }
    if (city == null || city.isBlank()) {
      goalsArea.setText("");
      stakeholderArea.setText("");
      logisticsArea.setText("");
      return;
    }
    BusinessTripProfile profile = businessTripProfileForCity(city);
    goalsArea.setText(profile.meetingGoals);
    stakeholderArea.setText(profile.stakeholderPlan);
    logisticsArea.setText(profile.logisticsPlan);
  }

  private static void saveBusinessTripProfile(String city, TextArea goalsArea, TextArea stakeholderArea,
      TextArea logisticsArea) {
    if (city == null || city.isBlank() || goalsArea == null || stakeholderArea == null || logisticsArea == null) {
      return;
    }
    BusinessTripProfile profile = businessTripProfileForCity(city);
    profile.meetingGoals = goalsArea.getText() == null ? "" : goalsArea.getText().trim();
    profile.stakeholderPlan = stakeholderArea.getText() == null ? "" : stakeholderArea.getText().trim();
    profile.logisticsPlan = logisticsArea.getText() == null ? "" : logisticsArea.getText().trim();
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
    return buildStructuredAlertPrompt(row.getFrom(), row.getTo(), row.getMode(), question, liveAlert);
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

  private static String normalizeAlertResponse(String answer, RouteLegRow row, LiveAlertData liveAlert) {
    Map<String, String> fields = parseAlertFields(answer);
    String websiteFallback = buildAlertFromWebsiteData(row, liveAlert);
    Map<String, String> fallbackFields = parseAlertFields(websiteFallback);
    for (Map.Entry<String, String> entry : fallbackFields.entrySet()) {
      String value = fields.get(entry.getKey());
      if (value == null || value.isBlank() || "Unknown".equalsIgnoreCase(value)) {
        fields.put(entry.getKey(), entry.getValue());
      }
    }
    return buildAlertFields(fields.get("Weather expected"),
        fields.get("Delays with travel"),
        fields.get("Events in the city"),
        fields.get("Health warnings"),
        fields.get("AQI and hazard information"));
  }

  private static String buildStructuredAlertPrompt(String from, String to, String mode, String question,
      LiveAlertData liveAlert) {
    StringBuilder prompt = new StringBuilder();
    prompt.append("You are a travel risk assistant. Analyze the route from ")
        .append(from).append(" to ").append(to).append(" via ").append(mode).append(". ")
        .append("Return valid JSON only with exactly these keys: ")
        .append("weather, delays, events, health, aqi. ")
        .append("Each value must be a short plain-English string. ")
        .append("Do not include markdown, code fences, or extra commentary. ");
    if (liveAlert != null) {
      if (liveAlert.weather != null && !liveAlert.weather.isBlank()) {
        prompt.append("Known weather: ").append(liveAlert.weather).append(". ");
      }
      if (liveAlert.aqi != null && !liveAlert.aqi.isBlank()) {
        prompt.append("Known AQI: ").append(liveAlert.aqi).append(". ");
      }
    }
    if (question != null && !question.isBlank()) {
      prompt.append("Traveler question: ").append(question).append(". ");
    }
    prompt.append("If a field is unknown, set it to \"Unknown\".");
    return prompt.toString();
  }

  private static String buildAlertFromWebsiteData(RouteLegRow row, LiveAlertData liveAlert) {
    String weather = liveAlert != null ? liveAlert.weather : "Unknown";
    String aqi = liveAlert != null ? liveAlert.aqi : "Unknown";
    String events = buildWebsiteEventSummary(row);
    String delays = buildWebsiteDelaySummary(row, weather);
    String health = buildWebsiteHealthSummary(aqi);
    return "[Website fallback] " + buildAlertFields(weather, delays, events, health, aqi);
  }

  private static String buildWebsiteEventSummary(RouteLegRow row) {
    String summary = buildWikiSuggestion(row.getTo());
    if (summary == null || summary.isBlank() || summary.contains("unavailable")) {
      return "Website fallback: Check local venue and city calendars.";
    }
    return "Website fallback: " + summary;
  }

  private static String buildWebsiteDelaySummary(RouteLegRow row, String weather) {
    String mode = row.getMode() == null ? "travel" : row.getMode().toLowerCase();
    if (weather != null && !weather.isBlank() && !"Unknown".equalsIgnoreCase(weather)) {
      return "Website fallback: Monitor " + mode + " conditions; current weather is " + weather + ".";
    }
    return "Website fallback: Verify live " + mode + " delays with the linked news and carrier sources.";
  }

  private static String buildWebsiteHealthSummary(String aqi) {
    if (aqi == null || aqi.isBlank() || "Unknown".equalsIgnoreCase(aqi)) {
      return "Website fallback: No specific health signal available; verify local public-health updates.";
    }
    return "Website fallback: Air quality signal is " + aqi + ".";
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
