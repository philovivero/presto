/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.hive.orc;

import com.facebook.presto.hive.HiveClientConfig;
import com.facebook.presto.hive.HiveColumnHandle;
import com.facebook.presto.hive.HivePageSourceFactory;
import com.facebook.presto.hive.HivePartitionKey;
import com.facebook.presto.orc.OrcDataSource;
import com.facebook.presto.orc.OrcPredicate;
import com.facebook.presto.orc.OrcReader;
import com.facebook.presto.orc.OrcRecordReader;
import com.facebook.presto.orc.TupleDomainOrcPredicate;
import com.facebook.presto.orc.TupleDomainOrcPredicate.ColumnReference;
import com.facebook.presto.orc.metadata.MetadataReader;
import com.facebook.presto.orc.metadata.OrcMetadataReader;
import com.facebook.presto.spi.ConnectorPageSource;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.TupleDomain;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.units.DataSize;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.orc.OrcSerde;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.joda.time.DateTimeZone;

import javax.inject.Inject;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static com.facebook.presto.hive.HiveErrorCode.HIVE_CANNOT_OPEN_SPLIT;
import static com.facebook.presto.hive.HiveErrorCode.HIVE_MISSING_DATA;
import static com.facebook.presto.hive.HiveSessionProperties.getOrcMaxMergeDistance;
import static com.facebook.presto.hive.HiveSessionProperties.getOrcMaxReadSize;
import static com.facebook.presto.hive.HiveSessionProperties.isOptimizedReaderEnabled;
import static com.facebook.presto.hive.HiveUtil.getDeserializer;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.nullToEmpty;
import static io.airlift.units.DataSize.Unit.MEGABYTE;
import static java.lang.String.format;

public class OrcPageSourceFactory
        implements HivePageSourceFactory
{
    private final TypeManager typeManager;
    private final boolean enabled;
    private final DataSize orcMaxMergeDistance;
    private final DataSize orcMaxReadSize;

    @Inject
    public OrcPageSourceFactory(TypeManager typeManager, HiveClientConfig config)
    {
        //noinspection deprecation
        this(typeManager, config.isOptimizedReaderEnabled(), config.getOrcMaxMergeDistance(), config.getOrcMaxReadSize());
    }

    public OrcPageSourceFactory(TypeManager typeManager)
    {
        this(typeManager, true, new DataSize(1, MEGABYTE), new DataSize(8, MEGABYTE));
    }

    public OrcPageSourceFactory(TypeManager typeManager, boolean enabled, DataSize orcMaxMergeDistance, DataSize orcMaxReadSize)
    {
        this.typeManager = checkNotNull(typeManager, "typeManager is null");
        this.enabled = enabled;
        this.orcMaxMergeDistance = checkNotNull(orcMaxMergeDistance, "orcMaxMergeDistance is null");
        this.orcMaxReadSize = checkNotNull(orcMaxReadSize, "orcMaxReadSize is null");
    }

    @Override
    public Optional<? extends ConnectorPageSource> createPageSource(
            Configuration configuration,
            ConnectorSession session,
            Path path,
            long start,
            long length,
            Properties schema,
            List<HiveColumnHandle> columns,
            List<HivePartitionKey> partitionKeys,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            DateTimeZone hiveStorageTimeZone)
    {
        if (!isOptimizedReaderEnabled(session, enabled)) {
            return Optional.empty();
        }

        @SuppressWarnings("deprecation")
        Deserializer deserializer = getDeserializer(schema);
        if (!(deserializer instanceof OrcSerde)) {
            return Optional.empty();
        }

        return Optional.of(createOrcPageSource(
                new OrcMetadataReader(),
                configuration,
                path,
                start,
                length,
                columns,
                partitionKeys,
                effectivePredicate,
                hiveStorageTimeZone,
                typeManager,
                getOrcMaxMergeDistance(session, orcMaxMergeDistance),
                getOrcMaxReadSize(session, orcMaxReadSize)));
    }

    public static OrcPageSource createOrcPageSource(MetadataReader metadataReader,
            Configuration configuration,
            Path path,
            long start,
            long length,
            List<HiveColumnHandle> columns,
            List<HivePartitionKey> partitionKeys,
            TupleDomain<HiveColumnHandle> effectivePredicate,
            DateTimeZone hiveStorageTimeZone,
            TypeManager typeManager,
            DataSize maxMergeDistance,
            DataSize maxReadSize)
    {
        OrcDataSource orcDataSource;
        try {
            FileSystem fileSystem = path.getFileSystem(configuration);
            long size = fileSystem.getFileStatus(path).getLen();
            FSDataInputStream inputStream = fileSystem.open(path);
            orcDataSource = new HdfsOrcDataSource(path.toString(), inputStream, size, maxMergeDistance, maxReadSize);
        }
        catch (Exception e) {
            if (nullToEmpty(e.getMessage()).trim().equals("Filesystem closed") ||
                    e instanceof FileNotFoundException) {
                throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, e);
            }
            throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, splitError(e, path, start, length), e);
        }

        ImmutableSet.Builder<Integer> includedColumns = ImmutableSet.builder();
        ImmutableList.Builder<ColumnReference<HiveColumnHandle>> columnReferences = ImmutableList.builder();
        for (HiveColumnHandle column : columns) {
            if (!column.isPartitionKey()) {
                includedColumns.add(column.getHiveColumnIndex());
                Type type = typeManager.getType(column.getTypeSignature());
                columnReferences.add(new ColumnReference<>(column, column.getHiveColumnIndex(), type));
            }
        }

        OrcPredicate predicate = new TupleDomainOrcPredicate<>(effectivePredicate, columnReferences.build());

        try {
            OrcReader reader = new OrcReader(orcDataSource, metadataReader);
            OrcRecordReader recordReader = reader.createRecordReader(
                    includedColumns.build(),
                    predicate,
                    start,
                    length,
                    hiveStorageTimeZone);

            return new OrcPageSource(
                    recordReader,
                    orcDataSource,
                    partitionKeys,
                    columns,
                    hiveStorageTimeZone,
                    typeManager);
        }
        catch (Exception e) {
            try {
                orcDataSource.close();
            }
            catch (IOException ignored) {
            }
            if (e instanceof PrestoException) {
                throw (PrestoException) e;
            }
            String message = splitError(e, path, start, length);
            if (e.getClass().getSimpleName().equals("BlockMissingException")) {
                throw new PrestoException(HIVE_MISSING_DATA, message, e);
            }
            throw new PrestoException(HIVE_CANNOT_OPEN_SPLIT, message, e);
        }
    }

    private static String splitError(Throwable t, Path path, long start, long length)
    {
        return format("Error opening Hive split %s (offset=%s, length=%s): %s", path, start, length, t.getMessage());
    }
}
