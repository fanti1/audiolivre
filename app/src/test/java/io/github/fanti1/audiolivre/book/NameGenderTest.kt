package io.github.fanti1.audiolivre.book

import org.junit.Assert.assertEquals
import org.junit.Test

class NameGenderTest {

    private fun male(vararg names: String) = names.forEach {
        assertEquals("$it should read as a man", GenderHint.MALE, NameGender.of(it))
    }

    private fun female(vararg names: String) = names.forEach {
        assertEquals("$it should read as a woman", GenderHint.FEMALE, NameGender.of(it))
    }

    private fun unknown(vararg names: String) = names.forEach {
        assertEquals("$it should stay unknown", GenderHint.UNKNOWN, NameGender.of(it))
    }

    @Test
    fun `common Brazilian names are recognised`() {
        male("João", "Pedro", "Carlos", "Antônio", "Rafael", "Vitor", "Joaquim", "Nelson", "Luiz", "Marcos")
        female("Maria", "Ana", "Júlia", "Beatriz", "Carmen", "Inês", "Raquel", "Conceição", "Iracema", "Sandra")
    }

    @Test
    fun `endings decide the names nobody listed`() {
        // Invented or rare names still have to land somewhere sensible.
        male("Anselmo", "Teodoro", "Genaro", "Belmiro")
        female("Ermelinda", "Doracina", "Quitéria", "Zulmira")
    }

    @Test
    fun `names that end the wrong way are not fooled`() {
        // "-a" would say woman and "-o" man, but these are the other way round.
        male("José", "Garcia", "Jeremias", "Nicola", "Elias", "Tobias")
        female("Carmem", "Ruth", "Isabel", "Lourdes", "Solange", "Astrid")
    }

    @Test
    fun `a diminutive keeps the gender of its root`() {
        male("Bentinho", "Pedrinho", "Carlinhos")
        female("Mariazinha", "Aninha", "Luisinha")
    }

    @Test
    fun `a title alone is enough`() {
        male("Sr", "Senhor", "Dom", "Padre", "Doutor")
        female("Sra", "Senhora", "Dona", "Madre", "Senhorita")
    }

    @Test
    fun `an unclear word stays unknown so the narrator keeps reading`() {
        unknown("Ninguém", "Alguém", "XY", "J")
    }
}
