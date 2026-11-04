package com.you.youbackend;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TestRecordMapperTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TestRecordMapper testRecordMapper;

    @Test
    void connect() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
            System.out.println("当前数据库：" + connection.getCatalog());
        }
    }

    @Test
    @Transactional
    void crud() {
        // 1. 新增
        TestRecord record = new TestRecord();
        record.setName("小明");
        assertEquals(1, testRecordMapper.insert(record));

        Integer id = record.getId();
        assertNotNull(id);
        System.out.println("新增成功，id=" + id);

        // 2. 查询
        TestRecord loaded = testRecordMapper.selectById(id);
        assertNotNull(loaded);
        assertEquals("小明", loaded.getName());
        System.out.println("查询结果：" + loaded);

        // 3. 修改
        TestRecord patch = new TestRecord();
        patch.setId(id);
        patch.setName("小红");
        assertEquals(1, testRecordMapper.updateById(patch));

        TestRecord updated = testRecordMapper.selectById(id);
        assertNotNull(updated);
        assertEquals("小红", updated.getName());
        System.out.println("修改结果：" + updated);

        // 4. 删除刚新增的记录
        assertEquals(1, testRecordMapper.deleteById(id));
        assertNull(testRecordMapper.selectById(id));
        System.out.println("删除成功");
    }

    private static final String BUCKET = "demo-files";
    private static final String KEY = "notes/hello.txt";

    @Autowired
    private S3Client s3Client;

    @Test
    void uploadAndRead() {
        var upload = PutObjectRequest.builder()
                .bucket(BUCKET)
                .key(KEY)
                .contentType("text/plain; charset=utf-8")
                .build();

        s3Client.putObject(upload, RequestBody.fromString("Hello, RustFS"));

        var download = GetObjectRequest.builder()
                .bucket(BUCKET)
                .key(KEY)
                .build();

        String text = s3Client.getObjectAsBytes(download).asUtf8String();

        assertEquals("Hello, RustFS", text);
        System.out.println(BUCKET + "/" + KEY);
        System.out.println(text);
    }
}