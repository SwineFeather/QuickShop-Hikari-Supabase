-- QuickShop Supabase Sync Database Setup
-- Run this script in your Supabase SQL editor
-- IMPORTANT: This uses the OPTIMIZED SCHEMA with individual columns for better querying

-- Create optimized shops table
CREATE TABLE IF NOT EXISTS shops (
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

-- Create optimized transactions table
CREATE TABLE IF NOT EXISTS transactions (
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

-- Create indexes for better performance
CREATE INDEX IF NOT EXISTS idx_shops_owner ON shops(owner_uuid);
CREATE INDEX IF NOT EXISTS idx_shops_world ON shops(world);
CREATE INDEX IF NOT EXISTS idx_shops_type ON shops(type);
CREATE INDEX IF NOT EXISTS idx_shops_item_type ON shops(item_type);
CREATE INDEX IF NOT EXISTS idx_shops_last_updated ON shops(last_updated);

CREATE INDEX IF NOT EXISTS idx_transactions_shop_id ON transactions(shop_id);
CREATE INDEX IF NOT EXISTS idx_transactions_buyer ON transactions(buyer_uuid);
CREATE INDEX IF NOT EXISTS idx_transactions_seller ON transactions(seller_uuid);
CREATE INDEX IF NOT EXISTS idx_transactions_item_type ON transactions(item_type);
CREATE INDEX IF NOT EXISTS idx_transactions_timestamp ON transactions(transaction_timestamp);

-- Enable Row Level Security (recommended for production)
ALTER TABLE shops ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;

-- Create comprehensive policies for full access (required for plugin to work)
-- You may want to restrict these based on your security requirements
CREATE POLICY "Allow anonymous full access to shops" ON shops 
FOR ALL USING (true) WITH CHECK (true);

CREATE POLICY "Allow anonymous full access to transactions" ON transactions 
FOR ALL USING (true) WITH CHECK (true);

-- Create optimized view for shop statistics
CREATE OR REPLACE VIEW shop_stats AS
SELECT 
    s.id,
    s.owner_uuid,
    s.world,
    s.item_type,
    s.price,
    s.type,
    s.stock,
    s.unlimited,
    s.last_updated,
    COUNT(t.id) as total_transactions,
    COALESCE(SUM(t.total), 0) as total_revenue,
    COALESCE(SUM(t.quantity), 0) as total_items_sold
FROM shops s
LEFT JOIN transactions t ON s.id = t.shop_id
GROUP BY s.id, s.owner_uuid, s.world, s.item_type, s.price, s.type, s.stock, s.unlimited, s.last_updated;

-- Create function to get recent transactions
CREATE OR REPLACE FUNCTION get_recent_transactions(hours_back INTEGER DEFAULT 24)
RETURNS TABLE (
    id TEXT,
    shop_id TEXT,
    buyer_uuid TEXT,
    seller_uuid TEXT,
    item_type TEXT,
    item_amount INTEGER,
    quantity INTEGER,
    price_per_unit DECIMAL(10,2),
    total DECIMAL(10,2),
    tax DECIMAL(10,2),
    transaction_timestamp BIGINT
) AS $$
BEGIN
    RETURN QUERY
    SELECT 
        t.id,
        t.shop_id,
        t.buyer_uuid,
        t.seller_uuid,
        t.item_type,
        t.item_amount,
        t.quantity,
        t.price_per_unit,
        t.total,
        t.tax,
        t.transaction_timestamp
    FROM transactions t
    WHERE t.transaction_timestamp >= EXTRACT(EPOCH FROM (NOW() - INTERVAL '1 hour' * hours_back)) * 1000
    ORDER BY t.transaction_timestamp DESC;
END;
$$ LANGUAGE plpgsql;

-- Grant necessary permissions
GRANT USAGE ON SCHEMA public TO anon;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO anon;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO anon;

-- Create comments for documentation
COMMENT ON TABLE shops IS 'QuickShop shops synchronized from Minecraft server - optimized schema';
COMMENT ON TABLE transactions IS 'QuickShop transactions synchronized from Minecraft server - optimized schema';
COMMENT ON VIEW shop_stats IS 'Aggregated statistics for QuickShop shops'; 