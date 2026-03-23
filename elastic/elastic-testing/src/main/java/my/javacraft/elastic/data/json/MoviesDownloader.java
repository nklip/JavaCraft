package my.javacraft.elastic.data.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Slf4j
public class MoviesDownloader {

    public static void main(String[] args) throws IOException {
        exportImdbTop250Movies();
    }

    public static void exportImdbTop250Movies() throws IOException {
        MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
        headers.set(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate, br, zstd");
        headers.set(HttpHeaders.ACCEPT, "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");

        HttpEntity<String> entity = new HttpEntity<>(null, headers);
        RestTemplate restTemplate = new RestTemplate();
        ResponseEntity<String> httpResponse = restTemplate.exchange(
                "https://www.imdb.com/list/ls068082370/export?ref_=ttls_otexp",
                HttpMethod.GET,
                entity,
                String.class
        );

        try (PrintWriter out = new PrintWriter("movies.csv")) {
            assert httpResponse.getBody() != null;
            out.write(httpResponse.getBody());
        }

        transformCsvIntoJson();
    }

    public static void transformCsvIntoJson() throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();

        List<String> csvLines = Files.readAllLines(new File("movies.csv").toPath());

        List<Movie> movieList = new ArrayList<>();
        CSVParser parser = new CSVParserBuilder()
                .withSeparator(',')
                .withIgnoreQuotations(false)
                .build();
        for (int i = 1; i < csvLines.size(); i++) {
            String movieLine = csvLines.get(i);

            String[] columns = parser.parseLine(movieLine);

            // Position,Const,Created,Modified,Description,Title,URL,Title Type,IMDb Rating,Runtime (mins),Year,Genres,Num Votes,Release Date,Directors
            var position = columns[0]; // ranking
            var imdbId = columns[1];
            //var created = columns[2];
            //var modified = columns[3];
            var desc = columns[4]; // name
            var title = columns[5]; // name
            //var url = columns[6];
            //var imdbType = columns[7];
            //var imdbRating = columns[8];
            //var runtime = columns[9];
            var releaseYear = columns[10]; // release year
            var genres = columns[11]; // genres
            //var votes = columns[12];
            //var releaseDate = columns[13];
            var director = columns[14]; // director

            log.info("Processing {}...", title);

            try {
                Thread.sleep(500); // try not to spam the WEB-service

                MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
                HttpEntity<String> entity = new HttpEntity<>(null, headers);
                RestTemplate restTemplate = new RestTemplate();
                ResponseEntity<String> htmlResponse = restTemplate.exchange(
                        "http://www.omdbapi.com/?i=%s&apikey=1e60fc51".formatted(imdbId),
                        HttpMethod.GET,
                        entity,
                        String.class
                );

                TypeReference<Map<String, Object>> typeRef = new TypeReference<>() {};
                Map<String, Object> movieMap = objectMapper.readValue(htmlResponse.getBody(), typeRef);
                desc = (String)movieMap.get("Plot");
            } catch (Exception e) {
                log.debug(e.getMessage());
            }

            Movie movie = new Movie();
            movie.setName(title);
            movie.setDirector(director);
            movie.setRanking(Integer.parseInt(position));
            movie.setReleaseYear(Integer.parseInt(releaseYear));
            movie.setGenres(Arrays.stream(genres.replaceAll(" ", "").split(",")).toList());
            movie.setSynopsis(desc);

            movieList.add(movie);
        }

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(movieList);

        try (PrintWriter out = new PrintWriter("data/json/movies.json")) {
            out.write(json);
        }

    }

    @Data
    public static class Movie {
        String name;
        String director;
        Integer ranking;
        Integer releaseYear;
        List<String> genres;
        String synopsis;

        public Movie() {}
    }
}
