-- QuickShop Supabase Sync - Database Migration Script
-- This script will migrate from the old schema to the new optimized schema
-- Run this in your Supabase SQL editor

-- First, let's check what tables exist
DO $$
BEGIN
    -- Check if old shops table exists
    IF EXISTS (SELECT FROM information_schema.tables WHERE table_name = 'shops') THEN
        -- Check if it has the old 'item' column
        IF EXISTS (SELECT FROM information_schema.columns WHERE table_name = 'shops' AND column_name = 'item') THEN
            RAISE NOTICE 'Migrating from old schema to new optimized schema...';
            
            -- Create backup of old data
            CREATE TABLE IF NOT EXISTS shops_backup AS SELECT * FROM shops;
            CREATE TABLE IF NOT EXISTS transactions_backup AS SELECT * FROM transactions;
            
            -- Drop dependent objects
            DROP VIEW IF EXISTS shop_stats CASCADE;
            DROP FUNCTION IF EXISTS get_recent_transactions CASCADE;
            DROP TABLE IF EXISTS transactions CASCADE;
            DROP TABLE IF EXISTS shops CASCADE;
            
            -- Create new optimized schema
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
            
            RAISE NOTICE 'Migration completed. Old data backed up to shops_backup and transactions_backup tables.';
        ELSE
            RAISE NOTICE 'Shops table exists but does not have old schema. Creating new optimized schema...';
            
            -- Drop existing objects
            DROP VIEW IF EXISTS shop_stats CASCADE;
            DROP FUNCTION IF EXISTS get_recent_transactions CASCADE;
            DROP TABLE IF EXISTS transactions CASCADE;
            DROP TABLE IF EXISTS shops CASCADE;
            
            -- Create new optimized schema
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
        END IF;
    ELSE
        RAISE NOTICE 'No existing shops table found. Creating new optimized schema...';
        
        -- Create new optimized schema
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
    END IF;
END $$;

-- Create optimized indexes for better performance
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

-- Enable Row Level Security
ALTER TABLE shops ENABLE ROW LEVEL SECURITY;
ALTER TABLE transactions ENABLE ROW LEVEL SECURITY;

-- Drop existing policies if they exist
DROP POLICY IF EXISTS "Allow anonymous full access to shops" ON shops;
DROP POLICY IF EXISTS "Allow anonymous full access to transactions" ON transactions;

-- Create comprehensive policies
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

-- Verify the tables were created correctly
SELECT 'Migration completed successfully!' as status;
SELECT 'shops table structure:' as info;
SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'shops' ORDER BY ordinal_position;

SELECT 'transactions table structure:' as info;
SELECT column_name, data_type FROM information_schema.columns WHERE table_name = 'transactions' ORDER BY ordinal_position; 