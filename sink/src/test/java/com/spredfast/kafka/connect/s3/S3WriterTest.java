package com.spredfast.kafka.connect.s3;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.kafka.common.TopicPartition;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;
import com.spredfast.kafka.connect.s3.sink.BlockGZIPFileWriter;
import com.spredfast.kafka.connect.s3.sink.S3Writer;

/**
 * Really basic sanity check testing over the documented use of API.
 * I've not bothered to properly mock result objects etc. as it really only tests
 * how well I can mock S3 API beyond a certain point.
 */
public class S3WriterTest {

	private String testBucket = "kafka-connect-s3-unit-test";
	private String tmpDirPrefix = "S3WriterTest";
	private String tmpDir;

	public S3WriterTest() {

		String tempDir = System.getProperty("java.io.tmpdir");
		this.tmpDir = new File(tempDir, tmpDirPrefix).toString();

		System.out.println("Temp dir for writer test is: " + tmpDir);
	}

	@Before
	public void setUp() throws Exception {
		File f = new File(tmpDir);

		if (!f.exists()) {
			f.mkdir();
		}
	}

	private BlockGZIPFileWriter createDummmyFiles(long offset, int numRecords) throws Exception {
		//BlockGZIPFileWriter writer = new BlockGZIPFileWriter("20180201_car2go_vehicles_madrid", tmpDir, offset);
		BlockGZIPFileWriter writer = new BlockGZIPFileWriter("20180201_car2go_vehicles_madrid", tmpDir, offset);
		for (int i = 0; i < numRecords; i++) {
			writer.write(Arrays.asList(String.format("Record %d", i).getBytes()), 1);
		}
		writer.close();
		return writer;
	}


	private List <BlockGZIPFileWriter> createDummmyJsonFiles(long offset, int numRecords) throws Exception {
		String content = new String(Files.readAllBytes(Paths.get("test.json")));
		List <BlockGZIPFileWriter> res = new ArrayList<>();
		BlockGZIPFileWriter writer = null;
		for (int i = 0; i < numRecords; i++) {
			writer = new BlockGZIPFileWriter("20180201_car2go_vehicles_madrid", tmpDir, i);
			writer.write(Arrays.asList(content.getBytes()));
			writer.close();
			res.add(writer);
		}
		return res;
	}

	class ExpectedRequestParams {
		public String key;
		public String bucket;

		public ExpectedRequestParams(String k, String b) {
			key = k;
			bucket = b;
		}
	}

	private void verifyTMUpload(TransferManager mock, ExpectedRequestParams[] expect) {
		ArgumentCaptor<String> bucketCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
		verify(mock, times(expect.length)).upload(bucketCaptor.capture(), keyCaptor.capture()
			, any(File.class));

		List<String> bucketArgs = bucketCaptor.getAllValues();
		List<String> keyArgs = keyCaptor.getAllValues();

		for (int i = 0; i < expect.length; i++) {
			assertEquals(expect[i].bucket, bucketArgs.remove(0));
			assertEquals(expect[i].key, keyArgs.remove(0));
		}
	}

	private void verifyStringPut(AmazonS3 mock, String key, String content) throws Exception {
		ArgumentCaptor<PutObjectRequest> argument
			= ArgumentCaptor.forClass(PutObjectRequest.class);
		verify(mock)
			.putObject(argument.capture());

		PutObjectRequest req = argument.getValue();
		assertEquals(key, req.getKey());
		assertEquals(this.testBucket, req.getBucketName());

		InputStreamReader input = new InputStreamReader(req.getInputStream(), "UTF-8");
		StringBuilder sb = new StringBuilder(1024);
		final char[] buffer = new char[1024];
		try {
			for (int read = input.read(buffer, 0, buffer.length);
				 read != -1;
				 read = input.read(buffer, 0, buffer.length)) {
				sb.append(buffer, 0, read);
			}
		} catch (IOException ignore) {
		}

		assertEquals(content, sb.toString());
	}

	private String getKeyForFilename(String prefix, String name) {
		SimpleDateFormat df = new SimpleDateFormat("yyyy/MM/dd");
		return String.format("%s/%s/%s", prefix, df.format(new Date()), name);
	}

	@Test
	public void testUpload() throws Exception {
		AmazonS3 s3Mock = mock(AmazonS3.class);
		TransferManager tmMock = mock(TransferManager.class);

		BlockGZIPFileWriter fileWriter = createDummmyFiles(0, 1000);
		S3Writer s3Writer = new S3Writer(testBucket, "pfx", s3Mock, tmMock);
		TopicPartition tp = new TopicPartition("bar", 0);

		Upload mockUpload = mock(Upload.class);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000000.json")), isA(File.class)))
			.thenReturn(mockUpload);

		//when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000000.gz")), isA(File.class)))
		//	.thenReturn(mockUpload);
		//when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000000.index.json")), isA(File.class)))
		//	.thenReturn(mockUpload);
		s3Writer.putChunk(fileWriter.getOutputFileName(),tp);
		//s3Writer.putChunk(fileWriter.getDataFilePath(), fileWriter.getIndexFilePath(), tp);
		List<BlockGZIPFileWriter> writers = createDummmyJsonFiles(0, 10);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000001.json")), isA(File.class)))
			.thenReturn(mockUpload);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000002.json")), isA(File.class)))
			.thenReturn(mockUpload);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000003.json")), isA(File.class)))
			.thenReturn(mockUpload);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000004.json")), isA(File.class)))
			.thenReturn(mockUpload);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000005.json")), isA(File.class)))
			.thenReturn(mockUpload);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000006.json")), isA(File.class)))
			.thenReturn(mockUpload);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000007.json")), isA(File.class)))
			.thenReturn(mockUpload);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000008.json")), isA(File.class)))
			.thenReturn(mockUpload);
		when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000009.json")), isA(File.class)))
			.thenReturn(mockUpload);
		for (BlockGZIPFileWriter writer: writers)
		{
			//<mockUpload = mock(Upload.class);
			writer.getOutputFileName();
			s3Writer.putChunk(writer.getDataFilePath(),tp);
		}
		//s3Writer.putChunk(fileWriter.getOutputFileName(),tp);
/*
		List<BlockGZIPFileWriter> writers = createDummmyJsonFiles(0, 10);

		for (BlockGZIPFileWriter writer: writers)
		{
			mockUpload = mock(Upload.class);

			writer.getOutputFileName();
			when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000000.gz")), isA(File.class)))
				.thenReturn(mockUpload);
			when(tmMock.upload(eq(testBucket), eq(getKeyForFilename("pfx", "20180201_car2go_vehicles_madrid-000000000000.index.json")), isA(File.class)))
				.thenReturn(mockUpload);
			s3Writer.putChunk(writer.getDataFilePath(),fileWriter.getIndexFilePath(),tp);
		}
		*/

	}

	private S3Object makeMockS3Object(String key, String contents) throws Exception {
		S3Object mock = new S3Object();
		mock.setBucketName(this.testBucket);
		mock.setKey(key);
		InputStream stream = new ByteArrayInputStream(contents.getBytes("UTF-8"));
		mock.setObjectContent(stream);
		System.out.println("MADE MOCK FOR " + key + " WITH BODY: " + contents);
		return mock;
	}

	@Test
	public void testFetchOffsetNewTopic() throws Exception {
		AmazonS3 s3Mock = mock(AmazonS3.class);
		S3Writer s3Writer = new S3Writer(testBucket, "pfx", s3Mock);

		// Non existing topic should return 0 offset
		// Since the file won't exist. code will expect the initial fetch to 404
		AmazonS3Exception ase = new AmazonS3Exception("The specified key does not exist.");
		ase.setStatusCode(404);
		when(s3Mock.getObject(eq(testBucket), eq("pfx/last_chunk_index.new_topic-00000.txt")))
			.thenThrow(ase)
			.thenReturn(null);

		TopicPartition tp = new TopicPartition("new_topic", 0);
		long offset = s3Writer.fetchOffset(tp);
		assertEquals(0, offset);
		verify(s3Mock).getObject(eq(testBucket), eq("pfx/last_chunk_index.new_topic-00000.txt"));
	}

	//@Test
	public void testFetchOffsetExistingTopic() throws Exception {
		AmazonS3 s3Mock = mock(AmazonS3.class);
		S3Writer s3Writer = new S3Writer(testBucket, "pfx", s3Mock);
		// Existing topic should return correct offset
		// We expect 2 fetches, one for the cursor file
		// and second for the index file itself
		String indexKey = getKeyForFilename("pfx", "bar-00000-000000010042.index.json");

		when(s3Mock.getObject(eq(testBucket), eq("pfx/last_chunk_index.bar-00000.txt")))
			.thenReturn(
				makeMockS3Object("pfx/last_chunk_index.bar-00000.txt", indexKey)
			);

		when(s3Mock.getObject(eq(testBucket), eq(indexKey)))
			.thenReturn(
				makeMockS3Object(indexKey,
					"{\"chunks\":["
						// Assume 10 byte records, split into 3 chunks for same of checking the logic about next offset
						// We expect next offset to be 12031 + 34
						+ "{\"first_record_offset\":10042,\"num_records\":1000,\"byte_offset\":0,\"byte_length\":10000},"
						+ "{\"first_record_offset\":11042,\"num_records\":989,\"byte_offset\":10000,\"byte_length\":9890},"
						+ "{\"first_record_offset\":12031,\"num_records\":34,\"byte_offset\":19890,\"byte_length\":340}"
						+ "]}"
				)
			);

		TopicPartition tp = new TopicPartition("bar", 0);
		long offset = s3Writer.fetchOffset(tp);
		assertEquals(12031 + 34, offset);
		verify(s3Mock).getObject(eq(testBucket), eq("pfx/last_chunk_index.bar-00000.txt"));
		verify(s3Mock).getObject(eq(testBucket), eq(indexKey));

	}
}
