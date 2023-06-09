/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2011-2020 Broad Institute, Aiden Lab
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package juicebox.data;


import htsjdk.samtools.seekablestream.SeekableStream;
import htsjdk.tribble.util.LittleEndianInputStream;
import juicebox.HiC;
import juicebox.HiCGlobals;
import juicebox.tools.utils.original.IndexEntry;
import juicebox.windowui.HiCZoom;
import juicebox.windowui.NormalizationHandler;
import juicebox.windowui.NormalizationType;
import org.broad.igv.Globals;
import org.broad.igv.exceptions.HttpResponseException;
import org.broad.igv.feature.Chromosome;
import org.broad.igv.util.CompressionUtils;
import org.broad.igv.util.ParsingUtils;
import org.broad.igv.util.stream.IGVSeekableStreamFactory;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * @author jrobinso
 * @since Aug 17, 2010
 */
public class DatasetReaderV2 extends AbstractDatasetReader {

    private static final int maxLengthEntryName = 100;
    /**
     * Cache of chromosome name -> array of restriction sites
     */
    private final Map<String, int[]> fragmentSitesCache = new HashMap<>();
    private final SeekableStream stream, backUpStream;
    private Map<String, IndexEntry> masterIndex;
    private Map<String, IndexEntry> normVectorIndex;
    private Dataset dataset = null;
    private int version = -1;
    private Map<String, FragIndexEntry> fragmentSitesIndex;
    private Map<String, Map<Integer, IndexEntry>> blockIndexMap;
    private long masterIndexPos;
    private long normVectorFilePosition;
    private boolean activeStatus = true;
    private final AtomicBoolean useMainStream = new AtomicBoolean();
    public static double[] globalTimeDiffThings = new double[5];

    @Override
    public Dataset read() throws IOException {

        try {
            long position = stream.position();

            // Read the header
            LittleEndianInputStream dis = new LittleEndianInputStream(new BufferedInputStream(stream));

            String magicString = dis.readString();
            position += magicString.length() + 1;
            if (!magicString.equals("HIC")) {
                throw new IOException("Magic string is not HIC, this does not appear to be a hic file.");
            }

            version = dis.readInt();
            position += 4;

            if (HiCGlobals.guiIsCurrentlyActive) {
                System.out.println("HiC file version: " + version);
            }
            masterIndexPos = dis.readLong();

            position += 8;

            // will set genomeId below
            String genomeId = dis.readString();
            position += genomeId.length() + 1;

            Map<String, String> attributes = new HashMap<>();
            // Attributes  (key-value pairs)
            if (version > 4) {
                int nAttributes = dis.readInt();
                position += 4;

                for (int i = 0; i < nAttributes; i++) {
                    String key = dis.readString();
                    position += key.length() + 1;

                    String value = dis.readString();
                    position += value.length() + 1;
                    attributes.put(key, value);
                }
            }

            dataset.setAttributes(attributes);

            if (dataset.getHiCFileScalingFactor() != null) {
                HiCGlobals.hicMapScale = Double.parseDouble(dataset.getHiCFileScalingFactor());
            }

            // Read chromosome dictionary
            int nchrs = dis.readInt();
            position += 4;

            List<Chromosome> chromosomes = new ArrayList<>(nchrs);
            for (int i = 0; i < nchrs; i++) {
                String name = dis.readString();
                position += name.length() + 1;

                int size = dis.readInt();
                position += 4;

                chromosomes.add(new Chromosome(i, name, size));
            }
            boolean createWholeChr = false;
            ChromosomeHandler chromosomeHandler = new ChromosomeHandler(chromosomes, genomeId, createWholeChr, true);

            dataset.setChromosomeHandler(chromosomeHandler);
            // guess genomeID from chromosomes
            String genomeId1 = chromosomeHandler.getGenomeID();
            // if cannot find matching genomeID, set based on file
            dataset.setGenomeId(genomeId1);

            int nBpResolutions = dis.readInt();
            position += 4;

            int[] bpBinSizes = new int[nBpResolutions];
            for (int i = 0; i < nBpResolutions; i++) {
                bpBinSizes[i] = dis.readInt();
                position += 4;
            }
            dataset.setBpZooms(bpBinSizes);

            int nFragResolutions = dis.readInt();
            position += 4;

            int[] fragBinSizes = new int[nFragResolutions];
            for (int i = 0; i < nFragResolutions; i++) {
                fragBinSizes[i] = dis.readInt();
                position += 4;
            }
            dataset.setFragZooms(fragBinSizes);

            // Now we need to skip  through stream reading # fragments, stream on buffer is not needed so null it to
            // prevent accidental use
            dis = null;
            if (nFragResolutions > 0) {
                stream.seek(position);
                fragmentSitesIndex = new HashMap<>();
                Map<String, Integer> map = new HashMap<>();
                String firstChrName = null;
                for (int i = 0; i < nchrs; i++) {
                    String chr = chromosomes.get(i).getName();
                    if (!chr.equals(Globals.CHR_ALL)) {
                        firstChrName = chr;
                    }
                    byte[] buffer = new byte[4];
                    stream.readFully(buffer);
                    int nSites = (new LittleEndianInputStream(new ByteArrayInputStream(buffer))).readInt();
                    position += 4;

                    FragIndexEntry entry = new FragIndexEntry(position, nSites);
                    fragmentSitesIndex.put(chr, entry);
                    map.put(chr, nSites);

                    stream.skip(nSites * 4);
                    position += nSites * 4;
                }
                if (firstChrName != null) {
                    dataset.setRestrictionEnzyme(map.get(firstChrName));
                }
                dataset.setFragmentCounts(map);
            }


            readFooter(masterIndexPos);


        } catch (IOException e) {
            System.err.println("Error reading dataset" + e.getLocalizedMessage());
            throw e;
        }


        return dataset;

    }

    private MatrixZoomData readMatrixZoomData(Chromosome chr1, Chromosome chr2, int[] chr1Sites, int[] chr2Sites,
                                              LittleEndianInputStream dis) throws IOException {

        HiC.Unit unit = HiC.valueOfUnit(dis.readString());
        dis.readInt();                // Old "zoom" index -- not used

        // Stats.  Not used yet, but we need to read them anyway
        double sumCounts = dis.readFloat();
        float occupiedCellCount = dis.readFloat();
        float stdDev = dis.readFloat();
        float percent95 = dis.readFloat();

        int binSize = dis.readInt();
        HiCZoom zoom = new HiCZoom(unit, binSize);
        // TODO: Default binSize value for "ALL" is 6197...(actually (genomeLength/1000)/500; depending on bug fix, could be 6191 for hg19); We need to make sure our maps hold a valid binSize value as default.

        int blockBinCount = dis.readInt();
        int blockColumnCount = dis.readInt();

        MatrixZoomData zd = new MatrixZoomData(chr1, chr2, zoom, blockBinCount, blockColumnCount, chr1Sites, chr2Sites,
                this);

        int nBlocks = dis.readInt();
        HashMap<Integer, IndexEntry> blockIndex = new HashMap<>(nBlocks);

        for (int b = 0; b < nBlocks; b++) {
            int blockNumber = dis.readInt();
            long filePosition = dis.readLong();
            int blockSizeInBytes = dis.readInt();
            blockIndex.put(blockNumber, new IndexEntry(filePosition, blockSizeInBytes));
        }
        blockIndexMap.put(zd.getKey(), blockIndex);

        int nBins1 = chr1.getLength() / binSize;
        int nBins2 = chr2.getLength() / binSize;
        double avgCount = (sumCounts / nBins1) / nBins2;   // <= trying to avoid overflows
        zd.setAverageCount(avgCount);

        return zd;
    }


    public String readStats() throws IOException {
        String statsFileName = path.substring(0, path.lastIndexOf('.')) + "_stats.html";
        String stats;
        BufferedReader reader = null;
        try {
            StringBuilder builder = new StringBuilder();
            reader = ParsingUtils.openBufferedReader(statsFileName);
            String nextLine;
            int count = 0; // if there is an big text file that happens to be named the same, don't read it forever
            while ((nextLine = reader.readLine()) != null && count < 1000) {
                builder.append(nextLine);
                builder.append("\n");
                count++;
            }
            stats = builder.toString();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }

        return stats;
    }

    @Override
    public List<JCheckBox> getCheckBoxes(List<ActionListener> actionListeners) {
        String truncatedName = HiCFileTools.getTruncatedText(getPath(), maxLengthEntryName);
        final JCheckBox checkBox = new JCheckBox(truncatedName);
        checkBox.setSelected(isActive());
        checkBox.setToolTipText(getPath());
        actionListeners.add(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                setActive(checkBox.isSelected());
            }
        });

        List<JCheckBox> checkBoxList = new ArrayList<>();
        checkBoxList.add(checkBox);
        return checkBoxList;
    }

    @Override
    public NormalizationVector getNormalizationVector(int chr1Idx, HiCZoom zoom, NormalizationType normalizationType) {
        return dataset.getNormalizationVector(chr1Idx, zoom, normalizationType);
    }

    public DatasetReaderV2(String path) throws IOException {

        super(path);
        this.stream = IGVSeekableStreamFactory.getInstance().getStreamFor(path);
        this.backUpStream = IGVSeekableStreamFactory.getInstance().getStreamFor(path);

        if (this.stream != null && backUpStream != null) {
            masterIndex = new HashMap<>();
            dataset = new Dataset(this);
        }
        blockIndexMap = new HashMap<>();
    }

    private String checkGraphs(String graphs) {
        boolean reset = false;
        if (graphs == null) reset = true;
        else {
            Scanner scanner = new Scanner(graphs);
            try {
                while (!scanner.next().equals("[")) ;

                for (int idx = 0; idx < 2000; idx++) {
                    scanner.nextLong();
                }

                while (!scanner.next().equals("[")) ;
                for (int idx = 0; idx < 201; idx++) {
                    scanner.nextInt();
                    scanner.nextInt();
                    scanner.nextInt();
                }

                while (!scanner.next().equals("[")) ;
                for (int idx = 0; idx < 100; idx++) {
                    scanner.nextInt();
                    scanner.nextInt();
                    scanner.nextInt();
                    scanner.nextInt();
                }
            } catch (NoSuchElementException exception) {
                reset = true;
            }
        }

/*        if (reset) {
            try {
                graphs = readGraphs(null);
            } catch (IOException e) {
                graphs = null;
            }
        }*/
        return graphs;

    }

    private String readGraphs(String graphFileName) throws IOException {
        String graphs;
        try (BufferedReader reader = ParsingUtils.openBufferedReader(graphFileName)) {
            if (reader == null) return null;
            StringBuilder builder = new StringBuilder();
            String nextLine;
            while ((nextLine = reader.readLine()) != null) {
                builder.append(nextLine);
                builder.append("\n");
            }
            graphs = builder.toString();
        } catch (IOException e) {
            System.err.println("Error while reading graphs file: " + e);
            graphs = null;
        }
        return graphs;
    }


    @Override
    public boolean isActive() {
        return activeStatus;
    }

    @Override
    public void setActive(boolean status) {
        activeStatus = status;
    }

    @Override
    public int getVersion() {
        return version;
    }

    private void readFooter(long position) throws IOException {

        stream.seek(position);

        //Get the size in bytes of the v5 footer, that is the footer up to normalization and normalized expected values
        byte[] buffer = new byte[4];
        stream.read(buffer);
        LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));
        int nBytes = dis.readInt();

        normVectorFilePosition = masterIndexPos + nBytes + 4;  // 4 bytes for the buffer size

        buffer = new byte[nBytes];
        stream.read(buffer);
        dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));

        int nEntries = dis.readInt();
        for (int i = 0; i < nEntries; i++) {
            String key = dis.readString();
            long filePosition = dis.readLong();
            int sizeInBytes = dis.readInt();
            masterIndex.put(key, new IndexEntry(filePosition, sizeInBytes));
        }

        Map<String, ExpectedValueFunction> expectedValuesMap = new LinkedHashMap<>();

        // Expected values from non-normalized matrix
        int nExpectedValues = dis.readInt();
        for (int i = 0; i < nExpectedValues; i++) {

            NormalizationType no = NormalizationHandler.NONE;
            String unitString = dis.readString();
            HiC.Unit unit = HiC.valueOfUnit(unitString);
            int binSize = dis.readInt();
            String key = unitString + "_" + binSize + "_" + no;

            int nValues = dis.readInt();
            double[] values = new double[nValues];
            for (int j = 0; j < nValues; j++) {
                values[j] = dis.readDouble();
            }

            int nNormalizationFactors = dis.readInt();
            Map<Integer, Double> normFactors = new LinkedHashMap<>();
            for (int j = 0; j < nNormalizationFactors; j++) {
                Integer chrIdx = dis.readInt();
                Double normFactor = dis.readDouble();
                normFactors.put(chrIdx, normFactor);
            }

            expectedValuesMap.put(key, new ExpectedValueFunctionImpl(no, unit, binSize, values, normFactors));
        }
        dataset.setExpectedValueFunctionMap(expectedValuesMap);

        // Normalized expected values (v6 and greater only)

        if (version >= 6) {

            //dis = new LittleEndianInputStream(new BufferedInputStream(stream, 512000));
            dis = new LittleEndianInputStream(new BufferedInputStream(stream, HiCGlobals.bufferSize));

            try {
                nExpectedValues = dis.readInt();
            } catch (EOFException | HttpResponseException e) {
                if (HiCGlobals.printVerboseComments) {
                    System.out.println("No normalization vectors");
                }
                return;
            }

            for (int i = 0; i < nExpectedValues; i++) {

                String typeString = dis.readString();
                String unitString = dis.readString();
                HiC.Unit unit = HiC.valueOfUnit(unitString);
                int binSize = dis.readInt();
                String key = unitString + "_" + binSize + "_" + typeString;

                int nValues = dis.readInt();
                double[] values = new double[nValues];
                for (int j = 0; j < nValues; j++) {
                    values[j] = dis.readDouble();
                }

                int nNormalizationFactors = dis.readInt();
                Map<Integer, Double> normFactors = new LinkedHashMap<>();
                for (int j = 0; j < nNormalizationFactors; j++) {
                    Integer chrIdx = dis.readInt();
                    Double normFactor = dis.readDouble();
                    normFactors.put(chrIdx, normFactor);
                }

                NormalizationType type = dataset.getNormalizationHandler().getNormTypeFromString(typeString);
                ExpectedValueFunction df = new ExpectedValueFunctionImpl(type, unit, binSize, values, normFactors);
                expectedValuesMap.put(key, df);
            }

            // Normalization vectors (indexed)

            nEntries = dis.readInt();
            normVectorIndex = new HashMap<>(nEntries * 2);
            for (int i = 0; i < nEntries; i++) {

                NormalizationType type = dataset.getNormalizationHandler().getNormTypeFromString(dis.readString());
                int chrIdx = dis.readInt();
                String unit = dis.readString();
                int resolution = dis.readInt();
                long filePosition = dis.readLong();
                int sizeInBytes = dis.readInt();

                String key = NormalizationVector.getKey(type, chrIdx, unit, resolution);

                dataset.addNormalizationType(type);

                normVectorIndex.put(key, new IndexEntry(filePosition, sizeInBytes));
            }
        }
    }

    private int[] readSites(long position, int nSites) throws IOException {
        IndexEntry idx = new IndexEntry(position, 4 + nSites * 4);
        byte[] buffer = seekAndFullyReadCompressedBytes(idx);
        LittleEndianInputStream les = new LittleEndianInputStream(new ByteArrayInputStream(buffer));
        int[] sites = new int[nSites];
        for (int s = 0; s < nSites; s++) {
            sites[s] = les.readInt();
        }
        return sites;

    }

    @Override
    public Matrix readMatrix(String key) throws IOException {
        IndexEntry idx = masterIndex.get(key);
        if (idx == null) {
            return null;
        }

        byte[] buffer = seekAndFullyReadCompressedBytes(idx);
        LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));

        int c1 = dis.readInt();
        int c2 = dis.readInt();

        // TODO weird bug
        // interesting bug with local files; difficult to reliably repeat, but just occurs on loading a region
        // indices that are read (c1, c2) seem to be excessively large / wrong
        // maybe some int overflow is occurring?
        // uncomment next 2 lines to help debug
        // System.err.println("read in mtrx indcs "+c1+ "  " +c2+"  key  "+key+"    idx "+idx.position+
        //         " sz  "+idx.size+ " "+stream.getSource()+" "+stream.position()+" "+stream );
        if (c1 < 0 || c1 > dataset.getChromosomeHandler().getChromosomeArray().length ||
                c2 < 0 || c2 > dataset.getChromosomeHandler().getChromosomeArray().length) {
            return null;
        }

        Chromosome chr1 = dataset.getChromosomeHandler().getChromosomeFromIndex(c1);
        Chromosome chr2 = dataset.getChromosomeHandler().getChromosomeFromIndex(c2);

        // # of resolution levels (bp and frags)
        int nResolutions = dis.readInt();
        List<MatrixZoomData> zdList = new ArrayList<>();
        int[] chr1Sites = retrieveFragmentSitesFromCache(chr1);
        int[] chr2Sites = retrieveFragmentSitesFromCache(chr2);

        for (int i = 0; i < nResolutions; i++) {
            MatrixZoomData zd = readMatrixZoomData(chr1, chr2, chr1Sites, chr2Sites, dis);
            zdList.add(zd);
        }

        return new Matrix(c1, c2, zdList);
    }

    int getFragCount(Chromosome chromosome) {
        FragIndexEntry entry = null;
        if (fragmentSitesIndex != null)
            entry = fragmentSitesIndex.get(chromosome.getName());

        if (entry != null) {
            return entry.nSites;
        } else return -1;
    }

    private synchronized int[] retrieveFragmentSitesFromCache(Chromosome chromosome) throws IOException {
        int[] chrSites = fragmentSitesCache.get(chromosome.getName());
        if (chrSites == null && fragmentSitesIndex != null) {
            FragIndexEntry entry = fragmentSitesIndex.get(chromosome.getName());
            if (entry != null && entry.nSites > 0) {
                chrSites = readSites(entry.position, entry.nSites);
            }
            fragmentSitesCache.put(chromosome.getName(), chrSites);
        }
        return chrSites;
    }

    private final AtomicBoolean useMainCompression = new AtomicBoolean();

    @Override
    public List<Integer> getBlockNumbers(MatrixZoomData zd) {
        Map<Integer, IndexEntry> blockIndex = blockIndexMap.get(zd.getKey());
        return blockIndex == null ? null : new ArrayList<>(blockIndex.keySet());
    }

    private final CompressionUtils mainCompressionUtils = new CompressionUtils();
    private final CompressionUtils backUpCompressionUtils = new CompressionUtils();

    @Override
    public void close() {
        try {
            stream.close();
            backUpStream.close();
        } catch (IOException e) {
            e.printStackTrace();  //To change body of catch statement use File | Settings | File Templates.
        }
    }

    public Map<String, IndexEntry> getNormVectorIndex() {
        return normVectorIndex;
    }

    public long getNormFilePosition() {
        return version <= 5 ? (new File(this.path)).length() : normVectorFilePosition;
    }

    static class FragIndexEntry {
        final long position;
        final int nSites;

        FragIndexEntry(long position, int nSites) {
            this.position = position;
            this.nSites = nSites;
        }
    }

    @Override
    public NormalizationVector readNormalizationVector(NormalizationType type, int chrIdx, HiC.Unit unit, int binSize) throws IOException {

        String key = NormalizationVector.getKey(type, chrIdx, unit.toString(), binSize);
        if (normVectorIndex == null) return null;
        IndexEntry idx = normVectorIndex.get(key);
        if (idx == null) return null;

        byte[] buffer = seekAndFullyReadCompressedBytes(idx);
        LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));

        int nValues = dis.readInt();
        double[] values = new double[nValues];
        boolean allNaN = true;
        for (int i = 0; i < nValues; i++) {
            values[i] = dis.readDouble();
            if (!Double.isNaN(values[i])) {
                allNaN = false;
            }
        }
        if (allNaN) return null;
        else return new NormalizationVector(type, chrIdx, unit, binSize, values);
    }

    private byte[] seekAndFullyReadCompressedBytes(IndexEntry idx) throws IOException {

        boolean currentlyUseMainStream;
        byte[] compressedBytes = new byte[idx.size];

        synchronized (useMainStream) {
            currentlyUseMainStream = useMainStream.get();
            useMainStream.set(!currentlyUseMainStream);
        }

        if (currentlyUseMainStream) {
            synchronized (stream) {
                stream.seek(idx.position);
                stream.readFully(compressedBytes);
            }
        } else {
            synchronized (backUpStream) {
                backUpStream.seek(idx.position);
                backUpStream.readFully(compressedBytes);
            }
        }
        return compressedBytes;
    }

    @Override
    public Block readNormalizedBlock(int blockNumber, MatrixZoomData zd, NormalizationType no) throws IOException {

        if (no == null) {
            throw new IOException("Norm " + no + " is null");
        } else if (no.equals(NormalizationHandler.NONE)) {
            return readBlock(blockNumber, zd);
        } else {
            long[] timeDiffThings = new long[4];
            timeDiffThings[0] = System.currentTimeMillis();
            NormalizationVector nv1 = dataset.getNormalizationVector(zd.getChr1Idx(), zd.getZoom(), no);
            NormalizationVector nv2 = dataset.getNormalizationVector(zd.getChr2Idx(), zd.getZoom(), no);

            if (nv1 == null || nv2 == null) {
                if (HiCGlobals.printVerboseComments) { // todo should this print an error always instead?
                    System.err.println("Norm " + no + " missing for: " + zd.getDescription());
                    System.err.println(nv1 + " - " + nv2);
                }
                return null;
            }
            double[] nv1Data = nv1.getData();
            double[] nv2Data = nv2.getData();
            timeDiffThings[1] = System.currentTimeMillis();
            Block rawBlock = readBlock(blockNumber, zd);
            timeDiffThings[2] = System.currentTimeMillis();
            if (rawBlock == null) return null;

            Collection<ContactRecord> records = rawBlock.getContactRecords();
            List<ContactRecord> normRecords = new ArrayList<>(records.size());
            for (ContactRecord rec : records) {
                int x = rec.getBinX();
                int y = rec.getBinY();
                float counts;
                if (nv1Data[x] != 0 && nv2Data[y] != 0 && !Double.isNaN(nv1Data[x]) && !Double.isNaN(nv2Data[y])) {
                    counts = (float) (rec.getCounts() / (nv1Data[x] * nv2Data[y]));
                } else {
                    counts = Float.NaN;
                }
                normRecords.add(new ContactRecord(x, y, counts));
            }
            timeDiffThings[3] = System.currentTimeMillis();

            //double sparsity = (normRecords.size() * 100) / (Preprocessor.BLOCK_SIZE * Preprocessor.BLOCK_SIZE);
            //System.out.println(sparsity);
            //if(HiCGlobals.printVerboseComments) {
            //    System.out.println("Time taken inside of reader " +
            //            (timeDiffThings[1] - timeDiffThings[0]) / 1000.0 + " - " + (timeDiffThings[2] - timeDiffThings[1]) / 1000.0 + " - " + (timeDiffThings[3] - timeDiffThings[2]) / 1000.0);
            //}

            return new Block(blockNumber, normRecords, zd.getBlockKey(blockNumber, no));
        }
    }

    private Block readBlock(int blockNumber, MatrixZoomData zd) throws IOException {

        long[] timeDiffThings = new long[6];
        timeDiffThings[0] = System.currentTimeMillis();

        Block b = null;
        Map<Integer, IndexEntry> blockIndex = blockIndexMap.get(zd.getKey());
        if (blockIndex != null) {

            IndexEntry idx = blockIndex.get(blockNumber);
            if (idx != null) {

                //System.out.println(" blockIndexPosition:" + idx.position);
                timeDiffThings[1] = System.currentTimeMillis();
                byte[] compressedBytes = seekAndFullyReadCompressedBytes(idx);
                timeDiffThings[2] = System.currentTimeMillis();
                byte[] buffer;

                try {
                    buffer = decompress(compressedBytes);
                    timeDiffThings[3] = System.currentTimeMillis();

                } catch (Exception e) {
                    throw new RuntimeException("Block read error: " + e.getMessage());
                }

                LittleEndianInputStream dis = new LittleEndianInputStream(new ByteArrayInputStream(buffer));
                int nRecords = dis.readInt();
                List<ContactRecord> records = new ArrayList<>(nRecords);
                timeDiffThings[4] = System.currentTimeMillis();

                if (version < 7) {
                    for (int i = 0; i < nRecords; i++) {
                        int binX = dis.readInt();
                        int binY = dis.readInt();
                        float counts = dis.readFloat();
                        records.add(new ContactRecord(binX, binY, counts));
                    }
                } else {

                    int binXOffset = dis.readInt();
                    int binYOffset = dis.readInt();

                    boolean useShort = dis.readByte() == 0;

                    byte type = dis.readByte();

                    switch (type) {
                        case 1:
                            // List-of-rows representation
                            int rowCount = dis.readShort();

                            for (int i = 0; i < rowCount; i++) {

                                int binY = binYOffset + dis.readShort();
                                int colCount = dis.readShort();

                                for (int j = 0; j < colCount; j++) {

                                    int binX = binXOffset + dis.readShort();
                                    float counts = useShort ? dis.readShort() : dis.readFloat();
                                    records.add(new ContactRecord(binX, binY, counts));
                                }
                            }
                            break;
                        case 2:

                            int nPts = dis.readInt();
                            int w = dis.readShort();

                            for (int i = 0; i < nPts; i++) {
                                //int idx = (p.y - binOffset2) * w + (p.x - binOffset1);
                                int row = i / w;
                                int col = i - row * w;
                                int bin1 = binXOffset + col;
                                int bin2 = binYOffset + row;

                                if (useShort) {
                                    short counts = dis.readShort();
                                    if (counts != Short.MIN_VALUE) {
                                        records.add(new ContactRecord(bin1, bin2, counts));
                                    }
                                } else {
                                    float counts = dis.readFloat();
                                    if (!Float.isNaN(counts)) {
                                        records.add(new ContactRecord(bin1, bin2, counts));
                                    }
                                }
                            }

                            break;
                        default:
                            throw new RuntimeException("Unknown block type: " + type);
                    }
                }
                b = new Block(blockNumber, records, zd.getBlockKey(blockNumber, NormalizationHandler.NONE));
                timeDiffThings[5] = System.currentTimeMillis();
                for (int ii = 0; ii < timeDiffThings.length - 1; ii++) {
                    globalTimeDiffThings[ii] += (timeDiffThings[ii + 1] - timeDiffThings[ii]) / 1000.0;
                }
            }
        }

        // If no block exists, mark with an "empty block" to prevent further attempts
        if (b == null) {
            b = new Block(blockNumber, zd.getBlockKey(blockNumber, NormalizationHandler.NONE));
        }
        return b;
    }

    private byte[] decompress(byte[] compressedBytes) {
        boolean currentlyUseMainCompression;

        synchronized (useMainCompression) {
            currentlyUseMainCompression = useMainCompression.get();
            useMainCompression.set(!currentlyUseMainCompression);
        }

        if (currentlyUseMainCompression) {
            synchronized (mainCompressionUtils) {
                return mainCompressionUtils.decompress(compressedBytes);
            }
        } else {
            synchronized (backUpCompressionUtils) {
                return backUpCompressionUtils.decompress(compressedBytes);
            }
        }
    }

    /*
    private static byte[] seekAndFullyReadCompressedBytes(SeekableStream stream, long positionInStream, int byteArraySize) throws IOException{
        byte[] compressedBytes = new byte[byteArraySize];
        stream.seek(positionInStream);
        stream.readFully(compressedBytes);
        return compressedBytes;
    }
    */
}
