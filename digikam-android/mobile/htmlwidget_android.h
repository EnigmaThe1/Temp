// Android-safe replacement for digiKam's Qt WebEngine map HTML widget.
#pragma once

#include <QStringList>
#include <QVariant>
#include <QWidget>

#include "geoifacecommon.h"
#include "geoifacetypes.h"
#include "geocoordinates.h"

namespace Digikam
{

class HTMLWidget : public QWidget
{
    Q_OBJECT

public:

    explicit HTMLWidget(QWidget* const parent = nullptr);
    ~HTMLWidget() override;

    void loadInitialHTML(const QString& initialHTML);
    QVariant runScript(const QString& scriptCode, bool async = true);
    bool runScript2Coordinates(const QString& scriptCode,
                               GeoCoordinates* const coordinates);
    void mouseModeChanged(const GeoMouseModes mouseMode);
    void setSelectionRectangle(const GeoCoordinates::Pair& searchCoordinates);
    void removeSelectionRectangle();
    void centerOn(qreal west, qreal north, qreal east, qreal south,
                  bool useSaneZoomLevel = true);
    void setSharedGeoIfaceObject(GeoIfaceSharedData* const sharedData);

Q_SIGNALS:

    void signalHTMLEvents(const QStringList& events);
    void signalMessageEvent(const QString& message);
    void signalJavaScriptReady();
    void selectionHasBeenMade(const Digikam::GeoCoordinates::Pair& coordinatesRect);

protected:

    bool eventFilter(QObject* object, QEvent* event) override;

protected Q_SLOTS:

    void slotHTMLCompleted(bool ok);
    void progress(int progress);

private:

    GeoIfaceSharedData* s = nullptr;
};

} // namespace Digikam
