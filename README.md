# pdf-split-lambda

AWS Lambda that splits a tagged PDF stored in S3 into multiple smaller PDFs, capped at a maximum byte size per output (default **80 MB**). Tagged-PDF structure, document language, and viewer preferences are preserved.

## Build

```
mvn -B package
```

Produces a shaded fat jar in `target/`. Lambda handler: `com.netralabs.PdfSplitter::handleRequest`.

## Request

The Lambda accepts a flat JSON object (`Map<String, String>`):

| Key           | Required | Description                                                       |
| ------------- | -------- | ----------------------------------------------------------------- |
| `bucket_name` | yes      | S3 bucket containing the source PDF                               |
| `file_name`   | yes      | Source PDF object name (with or without `.pdf`)                   |
| `folder_name` | no       | S3 prefix for the source object                                   |
| `max_mb`      | no       | Max size per output file in MB. Defaults to `80`.                 |

### Example request

```json
{
  "bucket_name": "my-pdf-bucket",
  "folder_name": "incoming/2024",
  "file_name": "german_doc_AOD.pdf",
  "max_mb": "80"
}
```

## Response

```json
{
  "error": false,
  "message": "Split completed"
}
```

On failure:

```json
{
  "error": true,
  "message": "The specified key does not exist in S3: incoming/2024/german_doc_AOD.pdf"
}
```

## Output

Split files are uploaded to:

```
s3://<bucket_name>/<folder_name>/<basename>_split/<basename>_<startPage>_<endPage>.pdf
```

For the request above, a 93-page input that splits into two parts would produce:

```
s3://my-pdf-bucket/incoming/2024/german_doc_AOD_split/german_doc_AOD_1_47.pdf
s3://my-pdf-bucket/incoming/2024/german_doc_AOD_split/german_doc_AOD_48_93.pdf
```

If a single page on its own exceeds `max_mb`, the response is:

```json
{
  "error": true,
  "message": "Page 12 alone exceeds 83886080 bytes. Consider downsampling or raising the limit."
}
```

## Local run (no AWS)

`com.netralabs.LocalRunner` splits a local file directly. Edit the constants at the top of `LocalRunner.java`:

```java
private static final String INPUT_FILE = "C:\\path\\to\\input.pdf";
private static final String OUTPUT_DIR = "";   // empty = <input-dir>/<basename>_split
private static final int    MAX_MB     = 80;
```

Then run `LocalRunner.main` from your IDE, or:

```
java -cp target/pdf-split-lambda-1.0-SNAPSHOT.jar com.netralabs.LocalRunner
```
