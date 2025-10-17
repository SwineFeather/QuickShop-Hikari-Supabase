package com.SwineFeather.quickshop.supabase;

import com.ghostchu.quickshop.api.QuickShopAPI;
import com.ghostchu.quickshop.api.shop.Shop;
import com.ghostchu.quickshop.api.shop.ShopManager;
import com.ghostchu.quickshop.api.shop.ShopType;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.Listener;



import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import java.util.Map;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class QuickShopSupabaseSync extends JavaPlugin implements Listener {

    private QuickShopAPI qsApi;
    private HttpClient httpClient;
    private String supabaseUrl;
    private String supabaseKey;
    private String shopsTable;
    private String transactionsTable;
    private boolean enabled;
    private long syncInterval;
    private boolean autoRecordTransactions;
    private double transactionTaxRate;
    private int requestTimeoutSeconds;
    private Gson gson = new Gson();
    private int syncTaskId = -1;
    private java.util.concurrent.ExecutorService executorService;
    private java.util.concurrent.Semaphore requestSemaphore;
    private java.util.Map<String, Integer> shopStockCache = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.Map<String, Double> shopPriceCache = new java.util.concurrent.ConcurrentHashMap<>();
    
    // Enhanced concurrency control fields
    private int maxConcurrentRequests;
    private int maxRequestsPerSecond;
    private int maxRetries;
    private long retryDelayMs;
    private double retryBackoffMultiplier;
    private int batchSize;
    private long batchDelayMs;
    private AtomicLong lastRequestTime = new AtomicLong(0);
    private AtomicInteger activeRequests = new AtomicInteger(0);
    private AtomicLong requestCounter = new AtomicLong(0);
    
    // Request queuing and batching
    private ConcurrentLinkedQueue<QueuedRequest> requestQueue = new ConcurrentLinkedQueue<>();
    private ConcurrentHashMap<String, Shop> pendingShopUpdates = new ConcurrentHashMap<>();
    private ConcurrentHashMap<String, Shop> pendingShopDeletions = new ConcurrentHashMap<>();
    private ConcurrentLinkedQueue<TransactionRequest> pendingTransactions = new ConcurrentLinkedQueue<>();
    
    // Rate limiting and processing
    private java.util.concurrent.ScheduledExecutorService requestProcessor;
    private ScheduledFuture<?> queueProcessorTask;
    private long lastQueueProcessTime = 0;
    
    // Request tracking for debugging
    private AtomicLong totalRequests = new AtomicLong(0);
    private AtomicLong failedRequests = new AtomicLong(0);
    private AtomicLong successfulRequests = new AtomicLong(0);

    // Request classes for queuing
    private static class QueuedRequest {
        final Runnable request;
        final String description;
        final long timestamp;
        final int priority; // 0 = high, 1 = normal, 2 = low
        
        QueuedRequest(Runnable request, String description, int priority) {
            this.request = request;
            this.description = description;
            this.timestamp = System.currentTimeMillis();
            this.priority = priority;
        }
    }
    
    private static class TransactionRequest {
        final String shopId;
        final UUID buyer;
        final UUID seller;
        final ItemStack item;
        final int quantity;
        final double pricePerUnit;
        final double total;
        final double tax;
        final long timestamp;
        
        TransactionRequest(String shopId, UUID buyer, UUID seller, ItemStack item, 
                          int quantity, double pricePerUnit, double total, double tax) {
            this.shopId = shopId;
            this.buyer = buyer;
            this.seller = seller;
            this.item = item;
            this.quantity = quantity;
            this.pricePerUnit = pricePerUnit;
            this.total = total;
            this.tax = tax;
            this.timestamp = System.currentTimeMillis();
        }
    }

    @Override
    public void onEnable() {
        getLogger().info("QuickShop Supabase Sync is starting...");
        
        try {
            saveDefaultConfig();
            reloadConfig();
            getLogger().info("Configuration loaded successfully.");
            
            enabled = getConfig().getBoolean("supabase.enabled", false);
            if (!enabled) {
                getLogger().info("Supabase sync disabled in config.");
                getLogger().info("QuickShop Supabase Sync loaded but disabled.");
                return;
            }

        // Validate configuration
        supabaseUrl = getConfig().getString("supabase.url");
        supabaseKey = getConfig().getString("supabase.key");
        
        if (supabaseUrl == null || supabaseKey == null || supabaseUrl.isEmpty() || supabaseKey.isEmpty()) {
            getLogger().severe("Supabase URL or key not configured! Please check your config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        shopsTable = getConfig().getString("supabase.shops_table", "shops");
        transactionsTable = getConfig().getString("supabase.transactions_table", "transactions");
        syncInterval = getConfig().getLong("supabase.sync_interval_seconds", 300) * 20L;
        autoRecordTransactions = getConfig().getBoolean("supabase.auto_record_transactions", true);
        transactionTaxRate = getConfig().getDouble("supabase.transaction_tax_rate", 5.0);
        
        // Load concurrency settings with more conservative defaults
        this.maxConcurrentRequests = getConfig().getInt("supabase.max_concurrent_requests", 2);
        this.requestTimeoutSeconds = getConfig().getInt("supabase.request_timeout_seconds", 15);
        this.maxRequestsPerSecond = getConfig().getInt("supabase.max_requests_per_second", 3);
        this.maxRetries = getConfig().getInt("supabase.max_retries", 3);
        this.retryDelayMs = getConfig().getLong("supabase.retry_delay_ms", 2000);
        this.retryBackoffMultiplier = getConfig().getDouble("supabase.retry_backoff_multiplier", 2.0);
        this.batchSize = getConfig().getInt("supabase.batch_size", 5);
        this.batchDelayMs = getConfig().getLong("supabase.batch_delay_ms", 1000);

        // Initialize HTTP client with connection pooling and longer timeout
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        
        // Initialize executor service for request processing
        executorService = java.util.concurrent.Executors.newFixedThreadPool(maxConcurrentRequests);
        requestSemaphore = new java.util.concurrent.Semaphore(maxConcurrentRequests);
        
        // Initialize request processor
        requestProcessor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();

        // Test connection
        try {
            testConnection();
            getLogger().info("Successfully connected to Supabase at: " + supabaseUrl);
        } catch (Exception e) {
            getLogger().severe("Failed to connect to Supabase: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Check if QuickShop-Hikari is available
        if (getServer().getPluginManager().getPlugin("QuickShop-Hikari") == null) {
            getLogger().severe("QuickShop-Hikari plugin not found! This plugin requires QuickShop-Hikari to be installed.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        try {
            qsApi = QuickShopAPI.getInstance();
            getLogger().info("QuickShop API initialized successfully.");
        } catch (Exception e) {
            getLogger().severe("Failed to initialize QuickShop API: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        
        getServer().getPluginManager().registerEvents(this, this);

        // Register for QuickShop events using reflection to avoid compilation issues
        registerQuickShopEvents();

        // Start request queue processor
        startRequestQueueProcessor();

        // Do NOT run any initial sync or batch processing on startup
        // Do NOT schedule or call syncAllShops() here
        boolean eventDriven = getConfig().getBoolean("supabase.event_driven_updates", true);
        
        if (eventDriven) {
            getLogger().info("Event-driven updates enabled - shops will sync on changes only.");
            syncTaskId = -1;
        } else {
            getLogger().info("Periodic sync enabled - but disabled by code for server-friendly startup.");
            syncTaskId = -1;
        }

        getLogger().info("QuickShop Supabase Sync enabled successfully!");
        getLogger().info("Shops table: " + shopsTable);
        getLogger().info("Transactions table: " + transactionsTable);
        getLogger().info("Concurrency settings: " + maxConcurrentRequests + " max concurrent requests, " + maxRequestsPerSecond + " requests/sec");
        getLogger().info("Retry settings: " + maxRetries + " max retries, " + retryDelayMs + "ms initial delay");
        getLogger().info("Batch settings: " + batchSize + " shops per batch, " + batchDelayMs + "ms between batches");
        getLogger().info("Request queuing and batching enabled to prevent timeout issues");
        
        } catch (Exception e) {
            getLogger().severe("Failed to start QuickShop Supabase Sync: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        getLogger().info("QuickShop Supabase Sync is shutting down...");
        
        // Stop queue processor
        if (queueProcessorTask != null) {
            queueProcessorTask.cancel(false);
        }
        
        if (syncTaskId != -1) {
            getServer().getScheduler().cancelTask(syncTaskId);
            getLogger().info("Cancelled sync task.");
        }
        
        // Process remaining requests before shutdown
        processRemainingRequests();
        
        // Shutdown executor services
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
            }
        }
        
        if (requestProcessor != null) {
            requestProcessor.shutdown();
            try {
                if (!requestProcessor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    requestProcessor.shutdownNow();
                }
            } catch (InterruptedException e) {
                requestProcessor.shutdownNow();
            }
        }
        
        // Log final statistics
        getLogger().info("Final statistics - Total: " + totalRequests.get() + 
                        ", Successful: " + successfulRequests.get() + 
                        ", Failed: " + failedRequests.get());
        
        getLogger().info("QuickShop Supabase Sync disabled.");
    }

    /**
     * Start the request queue processor
     */
    private void startRequestQueueProcessor() {
        queueProcessorTask = requestProcessor.scheduleAtFixedRate(() -> {
            try {
                processRequestQueue();
            } catch (Exception e) {
                getLogger().warning("Error in request queue processor: " + e.getMessage());
            }
        }, 2, 2, TimeUnit.SECONDS); // Process every 2 seconds instead of every second
        
        getLogger().info("Request queue processor started (2-second intervals)");
    }

    /**
     * Process the request queue with proper rate limiting and batching
     */
    private void processRequestQueue() {
        long now = System.currentTimeMillis();
        
        // Rate limiting check
        if (now - lastRequestTime.get() < (1000 / maxRequestsPerSecond)) {
            return; // Too soon for next request
        }
        
        // Check if we can make more requests
        if (activeRequests.get() >= maxConcurrentRequests) {
            return; // At capacity
        }
        
        // Process pending shop updates in batches
        processShopUpdateBatch();
        
        // Process pending shop deletions
        processShopDeletionBatch();
        
        // Process pending transactions
        processTransactionBatch();
        
        // Process queued requests
        processQueuedRequests();
        
        // Debug logging for queue status
        if (getLogger().isLoggable(java.util.logging.Level.FINE)) {
            getLogger().fine("Queue status - Active: " + activeRequests.get() + 
                           ", Pending updates: " + pendingShopUpdates.size() + 
                           ", Pending transactions: " + pendingTransactions.size() + 
                           ", Queued requests: " + requestQueue.size());
        }
    }

    /**
     * Process shop updates in batches
     */
    private void processShopUpdateBatch() {
        if (pendingShopUpdates.isEmpty()) return;
        
        int processed = 0;
        while (!pendingShopUpdates.isEmpty() && processed < batchSize) {
            Map.Entry<String, Shop> entry = pendingShopUpdates.entrySet().iterator().next();
            String shopId = entry.getKey();
            Shop shop = entry.getValue();
            pendingShopUpdates.remove(shopId);
            
            queueRequest(() -> {
                try {
                    upsertShop(shop);
                    getLogger().fine("Successfully updated shop at " + shop.getLocation() + " in Supabase");
                } catch (Exception e) {
                    getLogger().warning("Failed to update shop at " + shop.getLocation() + ": " + e.getMessage());
                    e.printStackTrace(); // Add stack trace for debugging
                }
            }, "upsert shop at " + shop.getLocation(), 1);
            
            processed++;
        }
        
        if (processed > 0) {
            getLogger().fine("Processed " + processed + " shop updates from queue");
        }
    }

    /**
     * Process shop deletions in batches
     */
    private void processShopDeletionBatch() {
        if (pendingShopDeletions.isEmpty()) return;
        
        int processed = 0;
        while (!pendingShopDeletions.isEmpty() && processed < batchSize) {
            Map.Entry<String, Shop> entry = pendingShopDeletions.entrySet().iterator().next();
            String shopId = entry.getKey();
            Shop shop = entry.getValue();
            pendingShopDeletions.remove(shopId);
            
            queueRequest(() -> {
                try {
                    deleteShop(shop);
                    getLogger().fine("Deleted shop at " + shop.getLocation() + " from Supabase");
                } catch (Exception e) {
                    getLogger().warning("Failed to delete shop at " + shop.getLocation() + ": " + e.getMessage());
                }
            }, "delete shop at " + shop.getLocation(), 1);
            
            processed++;
        }
        
        if (processed > 0) {
            getLogger().fine("Processed " + processed + " shop deletions from queue");
        }
    }

    /**
     * Process transactions in batches
     */
    private void processTransactionBatch() {
        if (pendingTransactions.isEmpty()) return;
        
        int processed = 0;
        while (!pendingTransactions.isEmpty() && processed < batchSize) {
            TransactionRequest transaction = pendingTransactions.poll();
            if (transaction == null) break;
            
            queueRequest(() -> {
                try {
                    insertTransaction(transaction.shopId, transaction.buyer, transaction.seller, 
                                    transaction.item, transaction.quantity, transaction.pricePerUnit, 
                                    transaction.total, transaction.tax);
                    getLogger().info("Successfully recorded transaction for shop " + transaction.shopId + 
                                   " - " + transaction.quantity + "x " + transaction.item.getType() + 
                                   " for " + transaction.total);
                } catch (Exception e) {
                    getLogger().warning("Failed to record transaction for shop " + transaction.shopId + ": " + e.getMessage());
                    e.printStackTrace(); // Add stack trace for debugging
                }
            }, "insert transaction for shop " + transaction.shopId, 2);
            
            processed++;
        }
        
        if (processed > 0) {
            getLogger().info("Processed " + processed + " transactions from queue");
        }
    }

    /**
     * Process queued requests with rate limiting
     */
    private void processQueuedRequests() {
        if (requestQueue.isEmpty()) return;
        
        long now = System.currentTimeMillis();
        long minInterval = 1000 / maxRequestsPerSecond;
        
        while (!requestQueue.isEmpty()) {
            if (now - lastRequestTime.get() < minInterval) {
                break; // Rate limit reached
            }
            
            if (activeRequests.get() >= maxConcurrentRequests) {
                break; // Concurrency limit reached
            }
            
            QueuedRequest queuedRequest = requestQueue.poll();
            if (queuedRequest == null) break;
            
            // Execute the request
            executeRequest(queuedRequest.request, queuedRequest.description);
        }
    }

    /**
     * Execute a request with proper tracking and rate limiting
     */
    private void executeRequest(Runnable request, String description) {
        totalRequests.incrementAndGet();
        activeRequests.incrementAndGet();
        lastRequestTime.set(System.currentTimeMillis());
        
        executorService.submit(() -> {
            try {
                request.run();
                successfulRequests.incrementAndGet();
                getLogger().fine("Request completed: " + description);
            } catch (Exception e) {
                failedRequests.incrementAndGet();
                getLogger().warning("Request failed: " + description + " - " + e.getMessage());
            } finally {
                activeRequests.decrementAndGet();
            }
        });
    }

    /**
     * Queue a request for processing
     */
    private void queueRequest(Runnable request, String description, int priority) {
        requestQueue.offer(new QueuedRequest(request, description, priority));
    }

    /**
     * Process remaining requests before shutdown
     */
    private void processRemainingRequests() {
        getLogger().info("Processing remaining requests before shutdown...");
        
        // Process all remaining requests
        while (!requestQueue.isEmpty() || !pendingShopUpdates.isEmpty() || 
               !pendingShopDeletions.isEmpty() || !pendingTransactions.isEmpty()) {
            
            processRequestQueue();
            
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }
        
        getLogger().info("Remaining requests processed");
    }

    /**
     * Rate limiting helper - ensures we don't exceed max requests per second
     */
    private void waitForRateLimit() {
        long now = System.currentTimeMillis();
        long lastRequest = lastRequestTime.get();
        long timeSinceLastRequest = now - lastRequest;
        long minInterval = 1000 / maxRequestsPerSecond; // milliseconds between requests
        
        if (timeSinceLastRequest < minInterval) {
            long waitTime = minInterval - timeSinceLastRequest;
            try {
                Thread.sleep(waitTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Functional interface for requests that can throw IOException
     */
    @FunctionalInterface
    private interface RequestSupplier<T> {
        T get() throws IOException, InterruptedException;
    }
    
    /**
     * Execute a request with retry logic and rate limiting
     */
    private <T> T executeWithRetry(RequestSupplier<T> requestSupplier, String operation) 
            throws IOException, InterruptedException {
        int attempts = 0;
        long delay = retryDelayMs;
        
        while (attempts < maxRetries) {
            try {
                // Wait for rate limit
                waitForRateLimit();
                
                // Acquire semaphore permit
                if (!requestSemaphore.tryAcquire(requestTimeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IOException("Request timeout - too many concurrent requests");
                }
                
                try {
                    return requestSupplier.get();
                } finally {
                    requestSemaphore.release();
                }
            } catch (IOException e) {
                attempts++;
                if (attempts >= maxRetries) {
                    throw e;
                }
                
                getLogger().warning("Request failed for " + operation + " (attempt " + attempts + "/" + maxRetries + "): " + e.getMessage());
                
                // Exponential backoff
                try {
                    Thread.sleep(delay);
                    delay = (long) (delay * retryBackoffMultiplier);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Request interrupted", ie);
                }
            }
        }
        
        throw new IOException("Max retries exceeded for " + operation);
    }

    private void testConnection() throws IOException, InterruptedException {
        executeWithRetry(() -> {
            try {
                String url = supabaseUrl + "/rest/v1/" + shopsTable + "?select=count";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("apikey", supabaseKey)
                        .header("Authorization", "Bearer " + supabaseKey)
                        .header("Content-Type", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    throw new IOException("Connection test failed with status: " + response.statusCode());
                }
                return null;
            } catch (InterruptedException e) {
                throw new IOException("Connection test interrupted", e);
            }
        }, "connection test");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("qss")) {
            return false;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("shopstats")) {
            if (args.length < 2) {
                sender.sendMessage("§cUsage: /qss shopstats <shop_id>");
                return true;
            }
            String shopId = args[1];
            try {
                String url = supabaseUrl + "/rest/v1/shop_stats?id=eq." + shopId + "&select=*";
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("apikey", supabaseKey)
                        .header("Authorization", "Bearer " + supabaseKey)
                        .header("Content-Type", "application/json")
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String body = response.body();
                    if (body != null && body.length() > 5) {
                        sender.sendMessage("§aShop Stats: " + body);
                    } else {
                        sender.sendMessage("§cNo stats found for shop: " + shopId);
                    }
                } else {
                    sender.sendMessage("§cFailed to fetch stats: " + response.statusCode());
                }
            } catch (Exception e) {
                sender.sendMessage("§cError fetching shop stats: " + e.getMessage());
            }
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage("§6QuickShop Supabase Sync Commands:");
            sender.sendMessage("§e/qss sync §7- Force sync all shops to Supabase");
            sender.sendMessage("§e/qss reload §7- Reload configuration");
            sender.sendMessage("§e/qss status §7- Show sync status");
            sender.sendMessage("§e/qss transaction <shop_id> <buyer_uuid> <quantity> [price] §7- Record a transaction");
            sender.sendMessage("§e/qss update <shop_id> §7- Update a specific shop");
            sender.sendMessage("§e/qss test-transaction <shop_id> §7- Test automatic transaction recording");
            sender.sendMessage("§e/qss force-process §7- Force immediate processing of pending requests");
            return true;
        }

        if (!sender.hasPermission("quickshop.supabase.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "sync":
                sender.sendMessage("§aStarting manual shop sync...");
                CompletableFuture.runAsync(() -> {
                    try {
                        int count = syncAllShops();
                        sender.sendMessage("§aManual sync completed! Synced " + count + " shops.");
                    } catch (Exception e) {
                        sender.sendMessage("§cManual sync failed: " + e.getMessage());
                    }
                });
                break;
                
            case "reload":
                reloadConfig();
                sender.sendMessage("§aConfiguration reloaded!");
                break;
                
            case "status":
                sender.sendMessage("§6QuickShop Supabase Sync Status:");
                sender.sendMessage("§eEnabled: §a" + enabled);
                sender.sendMessage("§eSync Interval: §a" + (syncInterval / 20) + " seconds");
                sender.sendMessage("§eShops Table: §a" + shopsTable);
                sender.sendMessage("§eTransactions Table: §a" + transactionsTable);
                sender.sendMessage("§eActive Requests: §a" + activeRequests.get() + "/" + maxConcurrentRequests);
                sender.sendMessage("§ePending Shop Updates: §a" + pendingShopUpdates.size());
                sender.sendMessage("§ePending Shop Deletions: §a" + pendingShopDeletions.size());
                sender.sendMessage("§ePending Transactions: §a" + pendingTransactions.size());
                sender.sendMessage("§eQueued Requests: §a" + requestQueue.size());
                sender.sendMessage("§eTotal Requests: §a" + totalRequests.get());
                sender.sendMessage("§eSuccessful Requests: §a" + successfulRequests.get());
                sender.sendMessage("§eFailed Requests: §c" + failedRequests.get());
                break;
                
            case "transaction":
                if (args.length < 4) {
                    sender.sendMessage("§cUsage: /qss transaction <shop_id> <buyer_uuid> <quantity> [price_per_unit]");
                    return true;
                }
                try {
                    String shopId = args[1];
                    UUID buyer = UUID.fromString(args[2]);
                    int quantity = Integer.parseInt(args[3]);
                    double pricePerUnit = args.length > 4 ? Double.parseDouble(args[4]) : 0.0;
                    
                    // Find the shop to get item and seller info
                    Shop shop = findShopById(shopId);
                    if (shop == null) {
                        sender.sendMessage("§cShop not found with ID: " + shopId);
                        return true;
                    }
                    
                    double total = pricePerUnit * quantity;
                    double tax = total * 0.05; // 5% tax example
                    
                    CompletableFuture.runAsync(() -> {
                        try {
                            insertTransaction(shopId, buyer, shop.getOwner().getUniqueId(), 
                                           shop.getItem(), quantity, pricePerUnit, total, tax);
                            sender.sendMessage("§aTransaction recorded successfully!");
                        } catch (Exception e) {
                            sender.sendMessage("§cFailed to record transaction: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    sender.sendMessage("§cInvalid arguments: " + e.getMessage());
                }
                break;
                
            case "update":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /qss update <shop_id>");
                    return true;
                }
                try {
                    String shopId = args[1];
                    Shop shop = findShopById(shopId);
                    if (shop == null) {
                        sender.sendMessage("§cShop not found with ID: " + shopId);
                        return true;
                    }
                    
                    updateShopAsync(shop);
                    sender.sendMessage("§aShop update queued successfully!");
                } catch (Exception e) {
                    sender.sendMessage("§cFailed to update shop: " + e.getMessage());
                }
                break;
                
            case "test-transaction":
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /qss test-transaction <shop_id>");
                    return true;
                }
                try {
                    String shopId = args[1];
                    Shop shop = findShopById(shopId);
                    if (shop == null) {
                        sender.sendMessage("§cShop not found with ID: " + shopId);
                        return true;
                    }
                    
                    // Simulate a transaction
                    UUID testBuyer = UUID.randomUUID();
                    int testQuantity = 1;
                    double testPrice = shop.getPrice();
                    
                    recordTransactionAsync(shop, testBuyer, testQuantity, testPrice);
                    sender.sendMessage("§aTest transaction recorded successfully!");
                    sender.sendMessage("§eBuyer: " + testBuyer);
                    sender.sendMessage("§eQuantity: " + testQuantity);
                    sender.sendMessage("§ePrice: " + testPrice);
                } catch (Exception e) {
                    sender.sendMessage("§cFailed to record test transaction: " + e.getMessage());
                }
                break;
                
            case "force-process":
                sender.sendMessage("§aForcing immediate processing of all pending requests...");
                processRequestQueue();
                sender.sendMessage("§aProcessing complete. Check /qss status for results.");
                break;
                
            default:
                sender.sendMessage("§cUnknown subcommand. Use /qss for help.");
                break;
        }
        
        return true;
    }

    private int syncAllShops() {
        if (!enabled) return 0;
        
        getLogger().info("Starting full shop sync...");
        int syncedCount = 0;
        int errorCount = 0;
        
        try {
            ShopManager shopManager = qsApi.getShopManager();
            List<Shop> allShops = shopManager.getAllShops();
            
            getLogger().info("Found " + allShops.size() + " shops to sync");
            
            // Process shops in batches to avoid overwhelming the system
            for (int i = 0; i < allShops.size(); i += batchSize) {
                int endIndex = Math.min(i + batchSize, allShops.size());
                List<Shop> batch = allShops.subList(i, endIndex);
                
                // Only log every 10th batch to reduce console spam
                if ((i / batchSize + 1) % 10 == 0 || i == 0) {
                    getLogger().info("Processing batch " + ((i / batchSize) + 1) + " of " + ((allShops.size() + batchSize - 1) / batchSize) + 
                                   " (" + batch.size() + " shops)");
                }
                
                for (Shop shop : batch) {
                    try {
                        // Check for shop changes and record transactions
                        checkShopChanges(shop);
                        
                        // Queue the shop update
                        String shopId = shop.getLocation().getWorld().getName() + ":" + 
                                       shop.getLocation().getBlockX() + ":" + 
                                       shop.getLocation().getBlockY() + ":" + 
                                       shop.getLocation().getBlockZ();
                        
                        pendingShopUpdates.put(shopId, shop);
                        syncedCount++;
                        
                    } catch (Exception e) {
                        errorCount++;
                        getLogger().warning("Failed to queue shop at " + shop.getLocation() + ": " + e.getMessage());
                    }
                }
                
                // Wait between batches to allow processing
                if (endIndex < allShops.size()) {
                    try {
                        Thread.sleep(batchDelayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            
            getLogger().info("Shop sync queued. Total shops: " + syncedCount + ", Errors: " + errorCount + 
                           " (processing in background)");
            return syncedCount;
            
        } catch (Exception e) {
            getLogger().severe("Failed to sync shops: " + e.getMessage());
            return 0;
        }
    }

        private JsonObject serializeItemToColumns(ItemStack item) {
        JsonObject itemData = new JsonObject();
        
        try {
            // Basic item data
            itemData.addProperty("item_type", item.getType().name());
            itemData.addProperty("item_amount", item.getAmount());
            itemData.addProperty("item_durability", item.getDurability());
            
            if (item.hasItemMeta()) {
                // Display name - use custom name if available, otherwise use default name
                if (item.getItemMeta().hasDisplayName()) {
                    itemData.addProperty("item_display_name", item.getItemMeta().getDisplayName());
                } else {
                    // Use the default display name for the item type
                    itemData.addProperty("item_display_name", getDefaultDisplayName(item.getType()));
                }
                
                // Lore
                if (item.getItemMeta().hasLore()) {
                    JsonArray loreArray = new JsonArray();
                    for (String loreLine : item.getItemMeta().getLore()) {
                        loreArray.add(loreLine);
                    }
                    itemData.add("item_lore", loreArray);
                }
                
                // Enchantments
                JsonObject enchants = new JsonObject();
                if (item.getItemMeta() instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta) {
                    // Enchanted book
                    org.bukkit.inventory.meta.EnchantmentStorageMeta enchMeta = (org.bukkit.inventory.meta.EnchantmentStorageMeta) item.getItemMeta();
                    if (enchMeta.hasStoredEnchants()) {
                        for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : enchMeta.getStoredEnchants().entrySet()) {
                            enchants.addProperty(entry.getKey().getKey().getKey(), entry.getValue());
                        }
                    }
                } else if (item.getItemMeta().hasEnchants()) {
                    // Regular enchanted items
                    for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> entry : item.getItemMeta().getEnchants().entrySet()) {
                        enchants.addProperty(entry.getKey().getKey().getKey(), entry.getValue());
                    }
                }
                
                if (enchants.size() > 0) {
                    itemData.add("item_enchants", enchants);
                }
                
                // Custom model data
                if (item.getItemMeta().hasCustomModelData()) {
                    itemData.addProperty("item_custom_model_data", item.getItemMeta().getCustomModelData());
                }
                
                // Unbreakable
                if (item.getItemMeta().isUnbreakable()) {
                    itemData.addProperty("item_unbreakable", true);
                }
            }
        } catch (Exception e) {
            // Fallback to basic item info
            itemData.addProperty("item_type", item.getType().name());
            itemData.addProperty("item_amount", item.getAmount());
            itemData.addProperty("item_durability", item.getDurability());
        }
        
        return itemData;
    }

    /**
     * Get the default display name for a material
     */
    private String getDefaultDisplayName(org.bukkit.Material material) {
        try {
            // Try to create a temporary ItemStack to get the default display name
            org.bukkit.inventory.ItemStack tempItem = new org.bukkit.inventory.ItemStack(material);
            if (tempItem.hasItemMeta() && tempItem.getItemMeta().hasDisplayName()) {
                return tempItem.getItemMeta().getDisplayName();
            }
            
            // Comprehensive fallback: convert material name to readable format
            String materialName = material.name().toLowerCase().replace('_', ' ');
            String[] words = materialName.split(" ");
            StringBuilder displayName = new StringBuilder();
            
            for (String word : words) {
                if (word.length() > 0) {
                    // Handle special cases
                    if (word.equals("of")) {
                        displayName.append("of ");
                    } else if (word.equals("the")) {
                        displayName.append("the ");
                    } else {
                        displayName.append(word.substring(0, 1).toUpperCase())
                                  .append(word.substring(1).toLowerCase())
                                  .append(" ");
                    }
                }
            }
            
            String result = displayName.toString().trim();
            
            // Handle special material names with proper display names
            switch (material) {
                case DIAMOND_SWORD: return "Diamond Sword";
                case IRON_SWORD: return "Iron Sword";
                case GOLDEN_SWORD: return "Golden Sword";
                case NETHERITE_SWORD: return "Netherite Sword";
                case DIAMOND_PICKAXE: return "Diamond Pickaxe";
                case IRON_PICKAXE: return "Iron Pickaxe";
                case GOLDEN_PICKAXE: return "Golden Pickaxe";
                case NETHERITE_PICKAXE: return "Netherite Pickaxe";
                case DIAMOND_AXE: return "Diamond Axe";
                case IRON_AXE: return "Iron Axe";
                case GOLDEN_AXE: return "Golden Axe";
                case NETHERITE_AXE: return "Netherite Axe";
                case DIAMOND_SHOVEL: return "Diamond Shovel";
                case IRON_SHOVEL: return "Iron Shovel";
                case GOLDEN_SHOVEL: return "Golden Shovel";
                case NETHERITE_SHOVEL: return "Netherite Shovel";
                case DIAMOND_HOE: return "Diamond Hoe";
                case IRON_HOE: return "Iron Hoe";
                case GOLDEN_HOE: return "Golden Hoe";
                case NETHERITE_HOE: return "Netherite Hoe";
                case DIAMOND_HELMET: return "Diamond Helmet";
                case IRON_HELMET: return "Iron Helmet";
                case GOLDEN_HELMET: return "Golden Helmet";
                case NETHERITE_HELMET: return "Netherite Helmet";
                case DIAMOND_CHESTPLATE: return "Diamond Chestplate";
                case IRON_CHESTPLATE: return "Iron Chestplate";
                case GOLDEN_CHESTPLATE: return "Golden Chestplate";
                case NETHERITE_CHESTPLATE: return "Netherite Chestplate";
                case DIAMOND_LEGGINGS: return "Diamond Leggings";
                case IRON_LEGGINGS: return "Iron Leggings";
                case GOLDEN_LEGGINGS: return "Golden Leggings";
                case NETHERITE_LEGGINGS: return "Netherite Leggings";
                case DIAMOND_BOOTS: return "Diamond Boots";
                case IRON_BOOTS: return "Iron Boots";
                case GOLDEN_BOOTS: return "Golden Boots";
                case NETHERITE_BOOTS: return "Netherite Boots";
                case DIAMOND_ORE: return "Diamond Ore";
                case IRON_ORE: return "Iron Ore";
                case GOLD_ORE: return "Gold Ore";
                case COAL_ORE: return "Coal Ore";
                case EMERALD_ORE: return "Emerald Ore";
                case LAPIS_ORE: return "Lapis Lazuli Ore";
                case REDSTONE_ORE: return "Redstone Ore";
                case NETHER_GOLD_ORE: return "Nether Gold Ore";
                case NETHER_QUARTZ_ORE: return "Nether Quartz Ore";
                case ANCIENT_DEBRIS: return "Ancient Debris";
                case DIAMOND_BLOCK: return "Diamond Block";
                case IRON_BLOCK: return "Iron Block";
                case GOLD_BLOCK: return "Gold Block";
                case EMERALD_BLOCK: return "Emerald Block";
                case LAPIS_BLOCK: return "Lapis Lazuli Block";
                case REDSTONE_BLOCK: return "Redstone Block";
                case QUARTZ_BLOCK: return "Quartz Block";
                case NETHERITE_BLOCK: return "Netherite Block";
                case DIAMOND: return "Diamond";
                case IRON_INGOT: return "Iron Ingot";
                case GOLD_INGOT: return "Gold Ingot";
                case EMERALD: return "Emerald";
                case LAPIS_LAZULI: return "Lapis Lazuli";
                case REDSTONE: return "Redstone";
                case QUARTZ: return "Quartz";
                case NETHERITE_INGOT: return "Netherite Ingot";
                case COAL: return "Coal";
                case CHARCOAL: return "Charcoal";
                case STICK: return "Stick";
                case STRING: return "String";
                case FEATHER: return "Feather";
                case FLINT: return "Flint";
                case LEATHER: return "Leather";
                case BONE: return "Bone";
                case SLIME_BALL: return "Slime Ball";
                case BLAZE_ROD: return "Blaze Rod";
                case GHAST_TEAR: return "Ghast Tear";
                case SPIDER_EYE: return "Spider Eye";
                case FERMENTED_SPIDER_EYE: return "Fermented Spider Eye";
                case MAGMA_CREAM: return "Magma Cream";
                case ENDER_PEARL: return "Ender Pearl";
                case ENDER_EYE: return "Eye of Ender";
                case GLOWSTONE_DUST: return "Glowstone Dust";
                case SUGAR: return "Sugar";
                case PAPER: return "Paper";
                case BOOK: return "Book";
                case ENCHANTED_BOOK: return "Enchanted Book";
                case EXPERIENCE_BOTTLE: return "Bottle o' Enchanting";
                case GLASS_BOTTLE: return "Glass Bottle";
                case POTION: return "Potion";
                case SPLASH_POTION: return "Splash Potion";
                case LINGERING_POTION: return "Lingering Potion";
                case TIPPED_ARROW: return "Tipped Arrow";
                case ARROW: return "Arrow";
                case SPECTRAL_ARROW: return "Spectral Arrow";
                case BOW: return "Bow";
                case CROSSBOW: return "Crossbow";
                case SHIELD: return "Shield";
                case TOTEM_OF_UNDYING: return "Totem of Undying";
                case TRIDENT: return "Trident";
                case FISHING_ROD: return "Fishing Rod";
                case CARROT_ON_A_STICK: return "Carrot on a Stick";
                case WARPED_FUNGUS_ON_A_STICK: return "Warped Fungus on a Stick";
                case ELYTRA: return "Elytra";
                case SADDLE: return "Saddle";
                case MINECART: return "Minecart";
                case CHEST_MINECART: return "Minecart with Chest";
                case FURNACE_MINECART: return "Minecart with Furnace";
                case TNT_MINECART: return "Minecart with TNT";
                case HOPPER_MINECART: return "Minecart with Hopper";
                case COMMAND_BLOCK_MINECART: return "Minecart with Command Block";
                case OAK_BOAT: return "Oak Boat";
                case SPRUCE_BOAT: return "Spruce Boat";
                case BIRCH_BOAT: return "Birch Boat";
                case JUNGLE_BOAT: return "Jungle Boat";
                case ACACIA_BOAT: return "Acacia Boat";
                case DARK_OAK_BOAT: return "Dark Oak Boat";
                case CHEST: return "Chest";
                case TRAPPED_CHEST: return "Trapped Chest";
                case ENDER_CHEST: return "Ender Chest";
                case SHULKER_BOX: return "Shulker Box";
                case WHITE_SHULKER_BOX: return "White Shulker Box";
                case ORANGE_SHULKER_BOX: return "Orange Shulker Box";
                case MAGENTA_SHULKER_BOX: return "Magenta Shulker Box";
                case LIGHT_BLUE_SHULKER_BOX: return "Light Blue Shulker Box";
                case YELLOW_SHULKER_BOX: return "Yellow Shulker Box";
                case LIME_SHULKER_BOX: return "Lime Shulker Box";
                case PINK_SHULKER_BOX: return "Pink Shulker Box";
                case GRAY_SHULKER_BOX: return "Gray Shulker Box";
                case LIGHT_GRAY_SHULKER_BOX: return "Light Gray Shulker Box";
                case CYAN_SHULKER_BOX: return "Cyan Shulker Box";
                case PURPLE_SHULKER_BOX: return "Purple Shulker Box";
                case BLUE_SHULKER_BOX: return "Blue Shulker Box";
                case BROWN_SHULKER_BOX: return "Brown Shulker Box";
                case GREEN_SHULKER_BOX: return "Green Shulker Box";
                case RED_SHULKER_BOX: return "Red Shulker Box";
                case BLACK_SHULKER_BOX: return "Black Shulker Box";
                default: return result;
            }
            
        } catch (Exception e) {
            // Final fallback: return the material name with underscores replaced
            return material.name().replace('_', ' ');
        }
    }

    /**
     * Register for QuickShop events using reflection to handle automatic updates
     */
    private void registerQuickShopEvents() {
        try {
            // Get the QuickShop plugin instance
            org.bukkit.plugin.Plugin qsPlugin = getServer().getPluginManager().getPlugin("QuickShop-Hikari");
            if (qsPlugin == null) {
                getLogger().warning("QuickShop-Hikari plugin not found for event registration");
                return;
            }

            // No event listeners needed - we use proximity detection when stock changes are detected
            // This is more reliable and performant than trying to track player interactions

            // Set up a task to monitor shop changes
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (!enabled) return;
                    
                    // Check for shop changes by comparing with last known state
                    List<Shop> currentShops = qsApi.getShopManager().getAllShops();
                    for (Shop shop : currentShops) {
                        // Check for transactions and update shop in Supabase
                        checkShopChanges(shop);
                    }
                }
            }.runTaskTimerAsynchronously(this, 20L * 5, 20L * 5); // Check every 5 seconds for better transaction detection

            getLogger().info("QuickShop event monitoring enabled");
        } catch (Exception e) {
            getLogger().warning("Failed to register QuickShop events: " + e.getMessage());
        }
    }

    /**
     * Update a single shop in Supabase (for event-driven updates)
     * Only queue update if a real change is detected.
     */
    public void updateShopAsync(Shop shop) {
        if (!enabled) return;
        String shopId = shop.getLocation().getWorld().getName() + ":" +
                       shop.getLocation().getBlockX() + ":" +
                       shop.getLocation().getBlockY() + ":" +
                       shop.getLocation().getBlockZ();
        boolean changed = false;
        Integer prevStock = shopStockCache.get(shopId);
        Double prevPrice = shopPriceCache.get(shopId);
        int currStock = shop.getRemainingStock();
        double currPrice = shop.getPrice();
        if (prevStock == null || prevStock != currStock) changed = true;
        if (prevPrice == null || prevPrice != currPrice) changed = true;
        if (changed) {
            shopStockCache.put(shopId, currStock);
            shopPriceCache.put(shopId, currPrice);
            pendingShopUpdates.put(shopId, shop);
            // Only log if not during startup (could use a flag if needed)
            // getLogger().info("[QSS] Queued shop update for " + shop.getLocation() + " (change detected)");
        } else {
            // getLogger().fine("[QSS] No real change for shop " + shop.getLocation() + ", not queuing update.");
        }
    }

    /**
     * Delete a shop from Supabase (for event-driven updates)
     */
    public void deleteShopAsync(Shop shop) {
        if (!enabled) return;
        
        // Queue the shop deletion instead of executing immediately
        String shopId = shop.getLocation().getWorld().getName() + ":" + 
                       shop.getLocation().getBlockX() + ":" + 
                       shop.getLocation().getBlockY() + ":" + 
                       shop.getLocation().getBlockZ();
        
        pendingShopDeletions.put(shopId, shop);
        getLogger().fine("Queued shop deletion for " + shop.getLocation());
    }

    /**
     * Record a transaction automatically (called when a purchase/sale occurs)
     */
    public void recordTransactionAsync(Shop shop, UUID buyer, int quantity, double pricePerUnit) {
        if (!enabled) return;
        
        String shopId = shop.getLocation().getWorld().getName() + ":" + 
                       shop.getLocation().getBlockX() + ":" + 
                       shop.getLocation().getBlockY() + ":" + 
                       shop.getLocation().getBlockZ();
        
        double total = pricePerUnit * quantity;
        double tax = total * (transactionTaxRate / 100.0); // Use configurable tax rate
        
        // Queue the transaction instead of executing immediately
        TransactionRequest transaction = new TransactionRequest(
            shopId, buyer, shop.getOwner().getUniqueId(), 
            shop.getItem(), quantity, pricePerUnit, total, tax
        );
        
        pendingTransactions.offer(transaction);
        getLogger().info("Queued transaction: " + buyer + " bought " + quantity + 
                       "x " + shop.getItem().getType() + " from shop at " + shop.getLocation() + 
                       " (Total: " + total + ", Tax: " + tax + ")");
        getLogger().info("Transaction queue size: " + pendingTransactions.size());
    }

    /**
     * Check for shop changes and automatically record transactions
     * Only queue update if a real change is detected.
     */
    public void checkShopChanges(Shop shop) {
        if (!enabled || !autoRecordTransactions) return;
        String shopId = shop.getLocation().getWorld().getName() + ":" +
                       shop.getLocation().getBlockX() + ":" +
                       shop.getLocation().getBlockY() + ":" +
                       shop.getLocation().getBlockZ();
        Integer previousStock = shopStockCache.get(shopId);
        Double previousPrice = shopPriceCache.get(shopId);
        int currentStock = shop.getRemainingStock();
        double currentPrice = shop.getPrice();
        boolean changed = false;
        if (previousStock == null || previousStock != currentStock) changed = true;
        if (previousPrice == null || previousPrice != currentPrice) changed = true;
        if (changed) {
            shopStockCache.put(shopId, currentStock);
            shopPriceCache.put(shopId, currentPrice);
            pendingShopUpdates.put(shopId, shop);
            // Only log if not during startup (could use a flag if needed)
            // getLogger().info("[QSS] Queued shop update for " + shop.getLocation() + " (change detected in checkShopChanges)");
        }
        // Transaction logic - find nearby player when stock changes
        if (previousStock != null && currentStock != previousStock) {
            int stockDifference = previousStock - currentStock;
            getLogger().info("[DEBUG] Stock change detected for shop " + shopId + ": " + previousStock + " -> " + currentStock + " (diff: " + stockDifference + ")");
            
            if (stockDifference > 0) {
                // Purchase detected - find closest player to the shop
                UUID buyer = findClosestPlayer(shop.getLocation(), 16.0);
                
                if (buyer != null) {
                    recordTransactionAsync(shop, buyer, stockDifference, currentPrice);
                    getLogger().info("Detected purchase: " + stockDifference + "x " + shop.getItem().getType() + " from shop at " + shop.getLocation() + " by " + buyer);
                    processRequestQueue();
                } else {
                    getLogger().warning("Cannot record transaction - no player found within 16 blocks of shop " + shopId);
                }
            } else if (stockDifference < 0) {
                // Sale detected (player sold to shop) - find closest player
                int soldAmount = Math.abs(stockDifference);
                UUID seller = findClosestPlayer(shop.getLocation(), 16.0);
                
                if (seller != null) {
                    recordTransactionAsync(shop, seller, soldAmount, currentPrice);
                    getLogger().info("Detected sale: " + soldAmount + "x " + shop.getItem().getType() + " to shop at " + shop.getLocation() + " by " + seller);
                    processRequestQueue();
                } else {
                    getLogger().warning("Cannot record transaction - no player found within 16 blocks of shop " + shopId);
                }
            }
        }
    }

    /**
     * Find the closest player to a shop location within a given radius
     * This is used to determine who made a transaction when stock changes
     */
    private UUID findClosestPlayer(org.bukkit.Location shopLoc, double maxDistance) {
        if (shopLoc == null || shopLoc.getWorld() == null) return null;
        
        org.bukkit.entity.Player closestPlayer = null;
        double closestDistance = maxDistance;
        
        // Get all players in the same world
        for (org.bukkit.entity.Player player : shopLoc.getWorld().getPlayers()) {
            double distance = player.getLocation().distance(shopLoc);
            
            if (distance <= maxDistance && distance < closestDistance) {
                closestPlayer = player;
                closestDistance = distance;
            }
        }
        
        if (closestPlayer != null) {
            getLogger().info("[DEBUG] Found closest player: " + closestPlayer.getName() + 
                           " (" + closestPlayer.getUniqueId() + ") at distance " + 
                           String.format("%.2f", closestDistance) + " blocks from shop");
            return closestPlayer.getUniqueId();
        }
        
        getLogger().warning("[DEBUG] No players found within " + maxDistance + " blocks of shop at " + shopLoc);
        return null;
    }
    
    private Shop findShopById(String shopId) {
        try {
            // Parse shop ID format: world:x:y:z
            String[] parts = shopId.split(":");
            if (parts.length != 4) {
                return null;
            }
            
            String worldName = parts[0];
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);
            
            org.bukkit.World world = getServer().getWorld(worldName);
            if (world == null) {
                return null;
            }
            
            org.bukkit.Location location = new org.bukkit.Location(world, x, y, z);
            return qsApi.getShopManager().getShop(location);
        } catch (Exception e) {
            return null;
        }
    }

    private void upsertShop(Shop shop) throws IOException, InterruptedException {
        if (!enabled) return;
        
        executeWithRetry(() -> {
            JsonObject data = new JsonObject();
            Location loc = shop.getLocation();
            data.addProperty("id", loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ());
            data.addProperty("owner_uuid", shop.getOwner().toString());
            data.addProperty("world", loc.getWorld().getName());
            data.addProperty("x", loc.getBlockX());
            data.addProperty("y", loc.getBlockY());
            data.addProperty("z", loc.getBlockZ());
            // Add item data using new schema
            JsonObject itemData = serializeItemToColumns(shop.getItem());
            for (Map.Entry<String, com.google.gson.JsonElement> entry : itemData.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    com.google.gson.JsonPrimitive primitive = entry.getValue().getAsJsonPrimitive();
                    if (primitive.isString()) {
                        data.addProperty(entry.getKey(), primitive.getAsString());
                    } else if (primitive.isNumber()) {
                        data.addProperty(entry.getKey(), primitive.getAsNumber());
                    } else if (primitive.isBoolean()) {
                        data.addProperty(entry.getKey(), primitive.getAsBoolean());
                    }
                } else if (entry.getValue().isJsonArray()) {
                    data.add(entry.getKey(), entry.getValue().getAsJsonArray());
                } else if (entry.getValue().isJsonObject()) {
                    data.add(entry.getKey(), entry.getValue().getAsJsonObject());
                }
            }
            // Handle price overflow by limiting to reasonable range
            double price = shop.getPrice();
            if (price > 99999999.99) {
                price = 99999999.99; // Max value for DECIMAL(10,2)
                getLogger().warning("Price for shop at " + shop.getLocation() + " was too large, capped at 99999999.99");
            }
            data.addProperty("price", price);
            data.addProperty("type", shop.getShopType() == ShopType.BUYING ? "buy" : "sell");
            data.addProperty("stock", shop.getRemainingStock());
            data.addProperty("unlimited", shop.isUnlimited());
            data.addProperty("last_updated", System.currentTimeMillis());

            String url = supabaseUrl + "/rest/v1/" + shopsTable;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("Content-Type", "application/json")
                    .header("Prefer", "resolution=merge-duplicates")
                    .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201 && response.statusCode() != 200) {
                throw new IOException("Failed to upsert shop: " + response.statusCode() + " - " + response.body());
            }
            return null;
        }, "upsert shop at " + shop.getLocation());
    }

    private void deleteShop(Shop shop) throws IOException, InterruptedException {
        if (!enabled) return;
        
        executeWithRetry(() -> {
            Location loc = shop.getLocation();
            String id = loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
            String url = supabaseUrl + "/rest/v1/" + shopsTable + "?id=eq." + id;
            
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .DELETE()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 204 && response.statusCode() != 200) {
                throw new IOException("Failed to delete shop: " + response.statusCode() + " - " + response.body());
            }
            return null;
        }, "delete shop at " + shop.getLocation());
    }

    // Note: Transaction tracking is simplified since the event API has changed
    // You can extend this later when the proper event API is available
    private void insertTransaction(String shopId, UUID buyer, UUID seller, ItemStack item, 
                                 int quantity, double pricePerUnit, double total, double tax) 
                                 throws IOException, InterruptedException {
        if (!enabled) return;
        
        executeWithRetry(() -> {
            JsonObject data = new JsonObject();
            data.addProperty("id", UUID.randomUUID().toString());
            data.addProperty("shop_id", shopId);
            data.addProperty("buyer_uuid", buyer.toString());
            data.addProperty("seller_uuid", seller.toString());
            // Add item data using new schema
            JsonObject itemData = serializeItemToColumns(item);
            for (Map.Entry<String, com.google.gson.JsonElement> entry : itemData.entrySet()) {
                if (entry.getValue().isJsonPrimitive()) {
                    com.google.gson.JsonPrimitive primitive = entry.getValue().getAsJsonPrimitive();
                    if (primitive.isString()) {
                        data.addProperty(entry.getKey(), primitive.getAsString());
                    } else if (primitive.isNumber()) {
                        data.addProperty(entry.getKey(), primitive.getAsNumber());
                    } else if (primitive.isBoolean()) {
                        data.addProperty(entry.getKey(), primitive.getAsBoolean());
                    }
                } else if (entry.getValue().isJsonArray()) {
                    data.add(entry.getKey(), entry.getValue().getAsJsonArray());
                } else if (entry.getValue().isJsonObject()) {
                    data.add(entry.getKey(), entry.getValue().getAsJsonObject());
                }
            }
            data.addProperty("quantity", quantity);
            data.addProperty("price_per_unit", pricePerUnit);
            data.addProperty("total", total);
            data.addProperty("tax", tax);
            data.addProperty("balance_without_tax", total - tax);
            data.addProperty("transaction_timestamp", System.currentTimeMillis());

            String url = supabaseUrl + "/rest/v1/" + transactionsTable;
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("apikey", supabaseKey)
                    .header("Authorization", "Bearer " + supabaseKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(data.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 201) {
                throw new IOException("Failed to insert transaction: " + response.statusCode() + " - " + response.body());
            }
            return null;
        }, "insert transaction for shop " + shopId);
    }
} 