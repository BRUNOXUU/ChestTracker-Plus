package red.jackf.chesttracker.scan;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/** The settings file, in {@code config/chesttrackermelhor/}. It is read again whenever it is needed, so no restart is needed. */
final class ScanConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChestTrackerScan.ID);
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("H:mm");

    private static final int DEFAULT_CORES = 2;
    private static final String CORES_KEY = "nucleos";
    private static final String SCHEDULE_KEY = "horarios_scan";
    private static final String TIME_ZONE_KEY = "fuso_horario";
    private static final String DEFAULT_SCHEDULE = "03:00";
    private static final String DEFAULT_TIME_ZONE = "America/Sao_Paulo";

    private static final String CORES_SECTION = """
            # Chest Tracker Melhor - configuracoes do servidor
            #
            # Quantos nucleos do processador o /chesttracker scan new pode usar.
            # Mais nucleos deixam a varredura mais rapida, mas pesam mais no servidor.
            # A alteracao vale na proxima varredura, sem reiniciar.
            %s=%d
            """.formatted(CORES_KEY, DEFAULT_CORES);

    private static final String SCHEDULE_SECTION = """

            # Horarios em que o servidor roda o /chesttracker scan new sozinho, separados por virgula.
            # Exemplo: horarios_scan=04:00,16:30
            # Vazio desativa. Vale apenas em servidores dedicados, sem precisar reiniciar.
            %s=%s

            # Fuso dos horarios acima. Hospedagens costumam usar outro fuso, por isso ele fica explicito.
            %s=%s
            """.formatted(SCHEDULE_KEY, DEFAULT_SCHEDULE, TIME_ZONE_KEY, DEFAULT_TIME_ZONE);

    private static String lastReportedSchedule = null;

    /** @param times the minutes of the day at which to scan; empty when disabled */
    record Schedule(Set<LocalTime> times, ZoneId zone) {
    }

    private ScanConfig() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("chesttrackermelhor").resolve("servidor.properties");
    }

    /** Creates the file, or adds the settings that older versions of it don't have, keeping what is already set. */
    static void createIfMissing() {
        Path file = file();
        try {
            Files.createDirectories(file.getParent());
            if (!Files.exists(file)) {
                Files.writeString(file, CORES_SECTION + SCHEDULE_SECTION, StandardCharsets.UTF_8);
            } else if (!Files.readString(file, StandardCharsets.UTF_8).contains(SCHEDULE_KEY)) {
                Files.writeString(file, Files.readString(file, StandardCharsets.UTF_8) + SCHEDULE_SECTION, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not create {}", file, e);
        }
    }

    private static Properties read() {
        createIfMissing();
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            LOGGER.warn("Could not read {}, using default settings", file(), e);
        }
        return properties;
    }

    /** @return the configured core count, kept between 1 and what this machine has */
    static int cores() {
        String value = read().getProperty(CORES_KEY, String.valueOf(DEFAULT_CORES)).strip();
        try {
            return Math.clamp(Integer.parseInt(value), 1, Runtime.getRuntime().availableProcessors());
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid value '{}' for {} in {}, using {}", value, CORES_KEY, file(), DEFAULT_CORES);
            return DEFAULT_CORES;
        }
    }

    static Schedule schedule() {
        Properties properties = read();
        String rawTimes = properties.getProperty(SCHEDULE_KEY, "").strip();
        String rawZone = properties.getProperty(TIME_ZONE_KEY, DEFAULT_TIME_ZONE).strip();

        // This runs every minute; warn about a bad value once, not every time.
        String combined = rawTimes + "|" + rawZone;
        boolean report = !Objects.equals(combined, lastReportedSchedule);
        lastReportedSchedule = combined;

        Set<LocalTime> times = new HashSet<>();
        for (String entry : rawTimes.split(",")) {
            String time = entry.strip();
            if (time.isEmpty()) continue;
            try {
                times.add(LocalTime.parse(time, TIME_FORMAT));
            } catch (DateTimeParseException e) {
                if (report) LOGGER.warn("Ignoring invalid time '{}' for {} in {}, expected HH:mm", time, SCHEDULE_KEY, file());
            }
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(rawZone);
        } catch (DateTimeException e) {
            zone = ZoneId.of(DEFAULT_TIME_ZONE);
            if (report) LOGGER.warn("Invalid time zone '{}' for {} in {}, using {}", rawZone, TIME_ZONE_KEY, file(), zone);
        }

        if (report && !times.isEmpty()) LOGGER.info("Scheduled world scans at {} ({})", times, zone);
        return new Schedule(times, zone);
    }
}
