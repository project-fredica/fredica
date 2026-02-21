import Sidebar from "~/components/sidebar/Sidebar";
import type { Route } from "./+types/crud.$entity";
import { useEffect, useMemo, useState } from "react";
import { SidebarLayout } from "~/components/sidebar/SidebarLayout";
import { useFetch } from "~/utils/requests";
import { CrudTable } from "~/components/crud/CrudTable";
import { DataSheet } from "~/components/crud_v1/datasheet/DataSheet";

export function meta({ params }: Route.MetaArgs) {
    return [
        { title: `${params.entity} - Fredica Crud` },
    ];
}

export default function Component({
    params,
}: Route.ComponentProps) {
    const entityName = params.entity.replace('-', '_')
    return (
        <SidebarLayout>
            <DataSheet entityName={entityName} />
        </SidebarLayout>
    )
} 