package util;

import com.opencsv.CSVWriter;
import dto.EntryDTO;
import org.apache.http.HttpException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.logging.Logger;

import static java.util.logging.Level.*;

/**
 * Non instantiable utility class for Downloader module with static methods for working with Issues online
 *
 * @author xmokros 456442@mail.muni.cz
 */
public final class DownloaderUtil {
    private final static Logger LOGGER = Logger.getLogger(DownloaderUtil.class.getName());

    private final static String UNLABELED_LABEL = "unlabeled";

    private DownloaderUtil() {}

    /**
     * Wrapping method for downloading and writing Issues into file.
     *
     * @param githubDownloadUsername
     * @param githubDownloadRepository
     * @param issuesState
     * @param labels
     * @param excludedLabels
     * @return
     * @throws Exception
     */
    public static String getIssues(String githubDownloadUsername, String githubDownloadRepository, String issuesState, String[] labels, String[] excludedLabels)
            throws Exception {
        LOGGER.log(INFO, "Getting Issues for --> " + githubDownloadUsername + "/" + githubDownloadRepository + " <-- with State --> " + issuesState +
                " for labels: " + String.join(" and ", labels) + (excludedLabels == null ? "" : " while excluding labels: " + String.join(" and ", excludedLabels)));

        String urlString = "https://api.github.com/repos/" + githubDownloadUsername + "/" + githubDownloadRepository + "/issues?state=" + issuesState;
        List<EntryDTO> listOfEntries = extractEntriesRecursively(urlString, labels, excludedLabels);

        String csvFileName = updateFileNameIfNeeded("../data/IssueCategorizer-" + githubDownloadUsername + "-" + githubDownloadRepository +
                "-issues-" + issuesState + "-labels-" + String.join("-", labels) + ".csv");
        writeEntriesIntoFile(csvFileName, listOfEntries);

        return csvFileName;
    }

    private static List<EntryDTO> extractEntriesRecursively(String urlString, String[] labels, String[] labelsToExclude)
            throws IOException, ParseException, HttpException {
        LOGGER.log(INFO, "Extracting Issues for --> " + urlString + " <--");

        URL url = new URL(urlString);
        String login = "IssueCategorizerUsername:IssueCategorizerPassword";
        String encoding = Base64.getEncoder().encodeToString((login).getBytes());

        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Basic " + encoding);
        connection.setRequestProperty("Content-Type", "application/json");
        int status = connection.getResponseCode();

        if (status != 200) {
            LOGGER.log(WARNING, "Connection status is " + status);
            throw new HttpException("Response code of connection: " + status);
        }

        BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        List<EntryDTO> listOfEntries = new ArrayList<>();
        String input;

        while ((input = in.readLine()) != null) {
            listOfEntries = getEntries(input, labels, labelsToExclude);
        }

        if (connection.getHeaderFields().containsKey("Link")) {
            LOGGER.log(FINE, "Getting Link Pager from --> " + urlString + " <--");
            Map<String, String> pagesMap = getPages(connection.getHeaderFields().get("Link").get(0));

            for (Map.Entry<String, String> pageEntry : pagesMap.entrySet()) {
                if (pageEntry.getKey().equals("next")) {
                    listOfEntries.addAll(extractEntriesRecursively(pageEntry.getValue(), labels, labelsToExclude));
                }
            }
        }

        in.close();
        connection.disconnect();
        return listOfEntries;
    }

    private static List<EntryDTO> getEntries(String content, String[] labelsToFind, String[] labelsToExclude) throws ParseException {
        JSONParser parser = new JSONParser();
        JSONArray jsonArray = (JSONArray) parser.parse(content);
        List<EntryDTO> listOfEntries = new ArrayList<>();

        for (int i = 0; i < jsonArray.size(); i++) {
            JSONObject jsonObject = (JSONObject) jsonArray.get(i);

            if (jsonObject.containsKey("pull_request")) {
                continue;
            }

            LOGGER.log(FINE, "Getting Label for Issue with Order Number : " + (i + 1) + ". with Title --> " + jsonObject.get("title") + " <--");
            List<String> labelNames = getLabelNames((JSONArray) jsonObject.get("labels"));
            List<String> labels = getEntryLabels(labelNames, labelsToFind, labelsToExclude);

            if (labels == null || labels.isEmpty()) {
                continue;
            }

            String title = jsonObject.get("title").toString();
            String body = jsonObject.get("body").toString();

            for (String label : labels) {
                if (label.equals(UNLABELED_LABEL)) {
                    label = "";
                }

                EntryDTO entry = new EntryDTO(title, body, label);
                LOGGER.log(FINE, "Adding Entry --> " + entry  + " <--");
                listOfEntries.add(entry);
            }
        }

        return listOfEntries;
    }

    private static List<String> getEntryLabels(List<String> labelNames, String[] labelsToFind, String[] labelsToExclude) {
        List<String> output = new ArrayList<>();

        if (labelsToExclude != null) {
            for (String labelToExclude : labelsToExclude) {
                if (labelNames.contains(labelToExclude)) {
                    return null;
                }
            }
        }

        for (String labelToFind : labelsToFind) {
            if (labelToFind.equals("all") && labelsToFind.length == 1) {
                if (labelNames.isEmpty()) {
                    output.add(UNLABELED_LABEL);
                    break;
                }

                for (String labelName : labelNames) {
                    output.add(labelName);
                }
                break;
            }

            if (labelNames.contains(labelToFind)) {
                output.add(labelToFind);
            }
        }

        if (labelNames.isEmpty() && Arrays.asList(labelsToFind).contains(UNLABELED_LABEL)) {
            output.add(UNLABELED_LABEL);
        }

        return output;
    }

    private static List<String> getLabelNames(JSONArray labels) {
        List<String> labelNames = new ArrayList<>();

        for (int i = 0; i < labels.size(); i++) {
            String label = ((JSONObject) labels.get(i)).get("name").toString();
            labelNames.add(label);
        }

        return labelNames;
    }

    private static Map<String, String> getPages(String linkFromHeader) {
        String[] linkMap = linkFromHeader.split(", ");
        Map<String, String> pagesMap = new HashMap<>();

        for (String linkMapEntry : linkMap) {
            String[] linkDouble = linkMapEntry.split("; ");
            String link = linkDouble[0].substring(1, linkDouble[0].length() - 1);
            String key = linkDouble[1].substring(5, linkDouble[1].length() - 1);

            LOGGER.log(FINE, "Adding Link Page : " + key + " with link --> " + " <--");

            pagesMap.put(key, link);
        }

        return pagesMap;
    }

    private static void writeEntriesIntoFile(String csvFileName, List<EntryDTO> listOfEntries) throws Exception {
        LOGGER.log(INFO, "Writing Entries into file --> " + csvFileName + " <--");

        if (!new File("../data").isDirectory()) {
            new File("../data").mkdirs();
        }

        try (CSVWriter writer = new CSVWriter(new FileWriter(new File(csvFileName)))) {
            String[] header = { "Id", "Title", "Body" , "Label" };
            writer.writeNext(header);

            for (int i = 1; i <= listOfEntries.size(); i++) {
                EntryDTO entryDTO = listOfEntries.get(i - 1);
                String[] entry = { String.valueOf(i), entryDTO.getTitle(), entryDTO.getBody(), entryDTO.getLabel() };
                writer.writeNext(entry);
            }
        }
    }

    private static String updateFileNameIfNeeded(String fileName) throws Exception {
        File file = new File(fileName);
        if (!file.exists() || file.isDirectory()) {
            return fileName;
        }

        String nameWithoutSuffix = fileName.substring(0, fileName.lastIndexOf('.'));

        if (nameWithoutSuffix.length() <= 0) {
            throw new Exception("File does not have a name.");
        }

        String output;
        if (Character.isDigit(nameWithoutSuffix.charAt(nameWithoutSuffix.length() - 1))) {
            int num = Character.getNumericValue(nameWithoutSuffix.charAt(nameWithoutSuffix.length() - 1));
            output = nameWithoutSuffix.substring(0, nameWithoutSuffix.length() - 1) + (num + 1) + fileName.substring(fileName.lastIndexOf('.'));
        } else {
            output = nameWithoutSuffix + 1 + fileName.substring(fileName.lastIndexOf('.'));
        }

        return updateFileNameIfNeeded(output);
    }
}
