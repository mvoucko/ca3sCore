package de.trustable.ca3s.core.service.util;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;

public class DateUtil {

	public static Date asDate(LocalDate localDate) {
		return Date.from(localDate.atStartOfDay().atZone(ZoneId.systemDefault()).toInstant());
	}

	public static Date asDate(LocalDateTime localDateTime) {
		return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
	}

	public static LocalDate asLocalDate(Date date) {
		return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
	}

	public static LocalDateTime asLocalDateTime(Date date) {
		return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDateTime();
	}

	public static LocalDate asLocalDateUTC(Date date) {
		return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.of("UTC").normalized()).toLocalDate();
	}

	public static LocalDateTime asLocalDateTimeUTC(Date date) {
		return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.of("UTC").normalized()).toLocalDateTime();
	}

}