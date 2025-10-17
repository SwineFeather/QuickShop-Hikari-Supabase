# QuickShop Supabase Sync

Syncs your QuickShop-Hikari shops and transactions to Supabase in real-time, so you can build web dashboards, analytics, or whatever else you want with your server's economy data.

## What it does

- Automatically syncs all shops to your Supabase database
- Records every transaction (who bought what, when, and for how much)
- Works in the background without lagging your server
- Simple commands to check status and force syncs
- Handles high-traffic shops with smart rate limiting

## Database Schema

The plugin stores data in two main tables. Item details are stored as individual columns (not JSON blobs) so you can easily query by item type, filter by enchantments, etc.

### Shops Table
```sql
CREATE TABLE shops (
    id TEXT PRIMARY KEY,
    owner_uuid TEXT NOT NULL,
    world TEXT NOT NULL,
    x INTEGER NOT NULL,
    y INTEGER NOT NULL,
    z INTEGER NOT NULL,
    item_type TEXT NOT NULL,
    item_amount INTEGER NOT NULL,
    item_durability INTEGER NOT NULL DEFAULT 0,
    item_display_name TEXT,
    item_lore TEXT[],
    item_enchants JSONB,
    item_custom_model_data INTEGER,
    item_unbreakable BOOLEAN DEFAULT false,
    price DECIMAL(10,2) NOT NULL,
    type TEXT NOT NULL CHECK (type IN ('buy', 'sell')),
    stock INTEGER NOT NULL,
    unlimited BOOLEAN NOT NULL DEFAULT false,
    last_updated BIGINT NOT NULL
);
```

### Transactions Table
```sql
CREATE TABLE transactions (
    id TEXT PRIMARY KEY,
    shop_id TEXT NOT NULL REFERENCES shops(id) ON DELETE CASCADE,
    buyer_uuid TEXT NOT NULL,
    seller_uuid TEXT NOT NULL,
    item_type TEXT NOT NULL,
    item_amount INTEGER NOT NULL,
    item_durability INTEGER NOT NULL DEFAULT 0,
    item_display_name TEXT,
    item_lore TEXT[],
    item_enchants JSONB,
    item_custom_model_data INTEGER,
    item_unbreakable BOOLEAN DEFAULT false,
    quantity INTEGER NOT NULL,
    price_per_unit DECIMAL(10,2) NOT NULL,
    total DECIMAL(10,2) NOT NULL,
    tax DECIMAL(10,2) NOT NULL,
    balance_without_tax DECIMAL(10,2) NOT NULL,
    transaction_timestamp BIGINT NOT NULL
);
```

## Requirements

- Minecraft 1.21+ server (Spigot/Paper)
- QuickShop-Hikari plugin
- Java 16+
- Supabase account (free tier works fine)

## Installation

### 1. Build the Plugin

```bash
# Clone the repository
git clone https://github.com/SwineFeather/QuickShop-Hikari-Supabase.git
cd QuickShop-Hikari-Supabase

# Build with Maven
mvn clean package
```

### 2. Install on Server

1. Copy the generated JAR from `target/QuickShopSupabaseSync-1.0.0.jar` to your server's `plugins/` folder
2. Restart your server
3. Edit the generated `plugins/QuickShopSupabaseSync/config.yml` with your Supabase credentials
4. Restart the server again

### 3. Database Setup

In your Supabase SQL editor, run the `database_setup.sql` file. That's it - it creates everything you need.

If you're upgrading from an older version, use `database_migration.sql` instead (it'll back up your old data first).

## Configuration

Edit `plugins/QuickShopSupabaseSync/config.yml`:

```yaml
# QuickShop Supabase Sync Configuration
supabase:
  # Enable or disable Supabase synchronization
  enabled: true
  
  # Your Supabase project URL (without /rest/v1/)
  url: https://your-project.supabase.co
  
  # Your Supabase anon/public key
  key: your-anon-key-here
  
  # Database table names
  shops_table: shops
  transactions_table: transactions
  
  # Sync interval in seconds (only used if event_driven_updates is false)
  sync_interval_seconds: 300
  
  # Enable event-driven updates (recommended)
  event_driven_updates: true
  
  # Enable automatic transaction recording
  auto_record_transactions: true
  
  # Transaction tax rate (percentage)
  transaction_tax_rate: 5.0
  
  # Concurrency settings (conservative defaults to prevent timeouts)
  max_concurrent_requests: 2
  request_timeout_seconds: 15
  max_requests_per_second: 3
  
  # Retry settings
  max_retries: 3
  retry_delay_ms: 2000
  retry_backoff_multiplier: 2.0
  
  # Batch settings for bulk operations
  batch_size: 3
  batch_delay_ms: 2000

# Logging configuration
logging:
  # Enable debug logging (more verbose)
  debug: false
  
  # Log sync operations
  log_sync: true
  
  # Log transaction details
  log_transactions: false
```

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/qss` | `quickshop.supabase.admin` | Show help and available commands |
| `/qss sync` | `quickshop.supabase.admin` | Force sync all shops to Supabase |
| `/qss reload` | `quickshop.supabase.admin` | Reload configuration |
| `/qss status` | `quickshop.supabase.admin` | Show sync status and configuration |

## Permissions

| Permission | Default | Description |
|------------|---------|-------------|
| `quickshop.supabase.admin` | `op` | Access to all admin commands |
| `quickshop.supabase.sync` | `op` | Manual sync operations |

## API Usage

Once the plugin is running, you can access the data from your website or application using Supabase's REST API.

### Query Examples

```javascript
// Get all shops
const { data } = await supabase.from('shops').select('*')

// Find diamond shops
const { data } = await supabase
  .from('shops')
  .select('*')
  .eq('item_type', 'DIAMOND')

// Recent transactions (last 24 hours)
const { data } = await supabase
  .from('transactions')
  .select('*')
  .gte('transaction_timestamp', Date.now() - 86400000)
  .order('transaction_timestamp', { ascending: false })

// Shop stats
const { data } = await supabase
  .from('shop_stats')
  .select('*')
  .eq('owner_uuid', 'player-uuid-here')
```

## Troubleshooting

**Plugin won't start?**  
Check your Supabase URL and key in the config file.

**No data showing up?**  
Make sure QuickShop-Hikari is installed and you've run the database_setup.sql script.

**Permission errors?**  
Your Supabase key needs full access. If you're using RLS policies, make sure they allow anonymous access.

Still stuck? Use `/qss status` to see what's happening, or enable debug logging in the config.

## Building from Source

```bash
git clone https://github.com/SwineFeather/QuickShop-Hikari-Supabase.git
cd QuickShop-Hikari-Supabase
mvn clean package
```

The compiled JAR will be in `target/`.

## Issues or Questions?

Open an issue on GitHub. Include your server version, plugin version, and any relevant error messages.

## Contributing

Pull requests welcome! Check out CONTRIBUTING.md for guidelines.

## License

MIT License - see LICENSE file for details.

---

Built for the Minecraft community. If this helps your server, consider giving it a star ⭐