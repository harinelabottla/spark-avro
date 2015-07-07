package com.databricks.spark.avro.avroRelation2

import com.databricks.spark.avro.AvroSaver
import org.apache.avro.generic.GenericRecord
import org.apache.avro.mapred.AvroKey
import org.apache.avro.mapreduce.AvroKeyOutputFormat
import org.apache.hadoop.fs.Path
import org.apache.hadoop.io.NullWritable
import org.apache.hadoop.mapreduce.{RecordWriter, TaskAttemptContext}
import org.apache.spark.sql.Row
import org.apache.spark.sql.sources.OutputWriter
import org.apache.spark.sql.types.StructType

// NOTE: This class is instantiated and used on executor side only, no need to be serializable.
private[avro] class AvroOutputWriter(path: String,
                                    context: TaskAttemptContext,
                                    schema: StructType,
                                    recordName: String,
                                    recordNamespace: String) extends OutputWriter  {

  private lazy val converter = AvroSaver.createConverter(schema, recordName, recordNamespace)

  private val recordWriter: RecordWriter[AvroKey[GenericRecord], NullWritable] = {

    new AvroKeyOutputFormat[GenericRecord]() {
      // Here we override `getDefaultWorkFile` for two reasons:
      //
      //  1. To allow appending.  We need to generate unique output file names to avoid
      //     overwriting existing files (either exist before the write job, or are just written
      //     by other tasks within the same write job).
      //
      //  2. To allow dynamic partitioning.  Default `getDefaultWorkFile` uses
      //     `FileOutputCommitter.getWorkPath()`, which points to the base directory of all
      //     partitions in the case of dynamic partitioning.
      override def getDefaultWorkFile(context: TaskAttemptContext, extension: String): Path = {
        val uniqueWriteJobId = context.getConfiguration.get("spark.sql.sources.writeJobUUID")
        val split = context.getTaskAttemptID.getTaskID.getId
        new Path(path, f"part-r-$split%05d-$uniqueWriteJobId$extension")
      }
    }.getRecordWriter(context)
  }

  override def write(row: Row): Unit = {
    val key = new AvroKey(converter(row).asInstanceOf[GenericRecord])
    recordWriter.write(key, NullWritable.get())
  }

  override def close(): Unit = recordWriter.close(context)
}
