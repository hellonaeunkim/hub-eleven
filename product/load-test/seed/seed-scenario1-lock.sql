USE hubEleven;

START TRANSACTION;

SET @test_product_id = UUID_TO_BIN('10000000-0000-0000-0000-000000000001');
SET @test_stock_id = UUID_TO_BIN('20000000-0000-0000-0000-000000000001');
SET @test_company_id = UUID_TO_BIN('30000000-0000-0000-0000-000000000001');
SET @test_hub_id = UUID_TO_BIN('40000000-0000-0000-0000-000000000001');
SET @initial_quantity = 100000000;

INSERT INTO p_product (
    product_id, name, company_id, hub_id,
    created_at, created_by, updated_at, updated_by, deleted_at, deleted_by
) VALUES (
    @test_product_id, 'k6-lock-test', @test_company_id, @test_hub_id,
    NOW(), 1, NOW(), 1, NULL, NULL
)
ON DUPLICATE KEY UPDATE
    name = 'k6-lock-test',
    company_id = @test_company_id,
    hub_id = @test_hub_id,
    updated_at = NOW(),
    updated_by = 1,
    deleted_at = NULL,
    deleted_by = NULL;

INSERT INTO p_stock (
    stock_id, product_id, company_id, hub_id, quantity,
    created_at, created_by, updated_at, updated_by, deleted_at, deleted_by
) VALUES (
    @test_stock_id, @test_product_id, @test_company_id, @test_hub_id, @initial_quantity,
    NOW(), 1, NOW(), 1, NULL, NULL
)
ON DUPLICATE KEY UPDATE
    product_id = @test_product_id,
    company_id = @test_company_id,
    hub_id = @test_hub_id,
    quantity = @initial_quantity,
    updated_at = NOW(),
    updated_by = 1,
    deleted_at = NULL,
    deleted_by = NULL;

COMMIT;

SELECT
    BIN_TO_UUID(p.product_id) AS product_id,
    p.name AS product_name,
    BIN_TO_UUID(p.company_id) AS product_company_id,
    BIN_TO_UUID(p.hub_id) AS product_hub_id,
    BIN_TO_UUID(s.stock_id) AS stock_id,
    s.quantity,
    s.created_at,
    s.updated_at
FROM p_product p
JOIN p_stock s ON s.product_id = p.product_id
WHERE p.product_id = @test_product_id;
