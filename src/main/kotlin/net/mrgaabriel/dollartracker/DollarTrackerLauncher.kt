package net.mrgaabriel.dollartracker

import com.github.kevinsawicki.http.HttpRequest
import com.github.salomonbrys.kotson.double
import com.github.salomonbrys.kotson.get
import com.github.salomonbrys.kotson.obj
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.*
import net.dv8tion.jda.api.entities.Activity
import org.slf4j.LoggerFactory
import java.awt.Color
import java.io.File
import java.time.OffsetDateTime
import kotlin.concurrent.thread

object DollarTrackerLauncher {

    val logger = LoggerFactory.getLogger(this::class.java)

    lateinit var jda: JDA
    lateinit var config: BotConfig

    @JvmStatic
    fun main(args: Array<String>) {
        val file = File("config.json")
        if (!file.exists()) {
            file.createNewFile()
            file.writeText(Gson().toJson(BotConfig()))

            return
        }

        val lastValueFile = File("last_value")
        if (!lastValueFile.exists()) {
            lastValueFile.createNewFile()
            lastValueFile.writeText("0")
        }

        config = Gson().fromJson(file.readText(Charsets.UTF_8), BotConfig::class.java)

        val builder = JDABuilder(AccountType.BOT)
            .setToken(config.clientToken)
            .setStatus(OnlineStatus.ONLINE)
            .setActivity(Activity.watching("o preço do dólar"))

        jda = builder.build().awaitReady()
        logger.info("OK! Bot iniciado - ${jda.selfUser.name}#${jda.selfUser.discriminator} - (${jda.selfUser.id})")

        GlobalScope.launch {
            while (true) {
                checkDollarPrice()

                delay(10 * (1000 * 60)) // Dez minutos
            }
        }

        thread(name = "Console Monitor") {
            while (true) {
                val next = readLine()!!.toLowerCase()

                when (next) {
                    "force_check" -> {
                        checkDollarPrice()
                    }
                }
            }
        }
    }

    fun checkDollarPrice() {
        logger.info("Verificando o preço do dólar!")

        val lastValueFile = File("last_value")

        if (!lastValueFile.exists()) {
            lastValueFile.createNewFile()
            lastValueFile.writeText("0")
        }

        val body = HttpRequest.get("http://free.currencyconverterapi.com/api/v5/convert?q=USD_BRL")
            .userAgent("Mozilla/5.0 (X11; Ubuntu; Linux x86_64; rv:64.0) Gecko/20100101 Firefox/64.0")
            .body()

        val payload = JsonParser().parse(body)

        val results = payload["results"].obj
        val usdbrl = results["USD_BRL"].obj
        val value = usdbrl["val"].double

        logger.info("Preço atual do dólar: ${value} BRL")

        val builder = EmbedBuilder()

        val lastValue = lastValueFile.readText().toDouble()

        if (value == lastValue) {
            logger.info("O preço não mudou!")
            return
        }

        if (value > lastValue) {
            logger.info("Dólar subiu! :(")

            builder.setTitle("Dólar subiu :(")

            builder.setDescription("Valor atual do dólar: `${value} BRL`")
            builder.setColor(Color.RED)

            builder.setTimestamp(OffsetDateTime.now())
        } else {
            logger.info("Dólar caiu! :)")

            builder.setTitle("Dólar caiu :)")

            builder.setDescription("Valor atual do dólar: `${value} BRL`")
            builder.setColor(Color.GREEN)

            builder.setTimestamp(OffsetDateTime.now())
        }

        lastValueFile.writeText("${value}")

        val channel = jda.getTextChannelById(config.channelId)
        channel.sendMessage(builder.build()).queue()
    }
}

data class BotConfig(val clientToken: String = "Client token do Bot", val channelId: String = "Channel ID")