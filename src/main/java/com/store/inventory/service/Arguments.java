package com.store.inventory.service;

/**
 * Validaciones de entrada del servicio. Todas lanzan {@link IllegalArgumentException}, que es lo que
 * el contrato promete para argumentos inválidos.
 */
final class Arguments {

    private Arguments() {
    }

    /**
     * @param value texto a validar
     * @param name  nombre del argumento para el mensaje
     * @throws IllegalArgumentException si es {@code null} o está en blanco
     */
    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /**
     * @param value valor a validar
     * @param name  nombre del argumento para el mensaje
     * @throws IllegalArgumentException si es {@code null}
     */
    static void requireNonNull(Object value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
    }

    /**
     * @param value cantidad a validar
     * @param name  nombre del argumento para el mensaje
     * @throws IllegalArgumentException si es cero o negativa
     */
    static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive, was " + value);
        }
    }
}
