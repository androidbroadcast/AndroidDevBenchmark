# Бенчмарк сборки Android проектов

Репозиторий содержит несколько проектов, для которые необходимо запустить тесты и зарепортить их результаты

## Методика проведения

### Настройка софта

1. Установить [Gradle Profiler](https://github.com/gradle/gradle-profiler)
    - Установка на Windows
        1. Скачайте прикрепленный ниже Gradle Profiler
        2. Добавьте из скаченного архива папку `bin` в `$PATH`
        
        Скачать Gradle Profiler можно [здесь](https://disk.yandex.ru/d/7li4YcBoU3i6CQ)
        
2. Установить JDK 11 (или обновить до последней версии) и сделать ее по умолчанию (прописать в JAVA_HOME). Для X86-64 брать версию OpenJDK, а для Apple M1 - Zulu ARM. Также убедитесь что она обновлена у нас до последней ревизии

### Подготовка компьютера перед запуском теста

- Отключить внешние дисплеи
- Ноутбук подключить к питанию
- Отключить антивирус
    - Настройки исключений для ускорения сборки на Windows
        - путь к папки проектов.
        - Папку Android SDK
        - Папку с Android Studio
        - Папку JDK (если не в Android Studio папки)
        - $HOME/AppData/Local/Google
        - $HOME/.android
        - $HOME/.gradle
        - Добавить в исключения и процессы. Можно указывать папки или полный путь
- По максимум закрыть все фоновые программы
    
    **ОБЯЗАТЕЛЬНО** Отключить Android Studio!
    
- Включить производительный режим в настройках
- Ноутбук ставить на плоскую твердую поверхность, чтобы не было проблем с охлаждением и забором воздуха
- Не трогать компьютер во время теста
- Отключить индексацию поиска/Spotlight или добавить папку с проектами для тестов в исключения

## Проведение теста

1. Клонировать репозиторий с тестовыми [проектами](https://github.com/androidbroadcast/AndroidDevBenchmark)
2. Скопировать в папку файл [local.properties](http://local.properties) (можно взять в любом вашем проекте, который был импортирован в Android Studio) или в переменные окружения добавить переменную ANDROID_SDK_DIR. Там указывается путь к SDK и NDK.

Запустить из терминала в папке проекта команду

```bash
gradle-profiler --benchmark --scenario-file performance.scenarios clean_build
```

## Отправить результаты

Отправляйте результаты через [форму](https://forms.gle/2Fy56UX3w9UgCP916). 

> **Важно на каждый компьютер и версию ОС форма заполняется отдельно**
>
